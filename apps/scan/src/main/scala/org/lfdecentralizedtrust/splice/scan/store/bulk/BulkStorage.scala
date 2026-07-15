// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, S3Config}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, S3BucketConnection, UpdateHistory}

import scala.concurrent.{ExecutionContext, Future}
import cats.implicits.*
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.PekkoRetryableService
import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorage.{
  acsCommittedKvStoreKey,
  acsStagingKvStoreKey,
  firstAcsSnapshotTimestampKvStoreKey,
  updatesCommittedKvStoreKey,
  updatesStagingKvStoreKey,
}

import scala.concurrent.duration.*

class BulkStorage(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    stagingS3Config: S3Config,
    committedS3Config: S3Config,
    acsSnapshotStore: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    currentMigrationId: Long,
    kvProvider: ScanKeyValueProvider,
    metricsFactory: LabeledMetricsFactory,
    automationConfig: AutomationConfig,
    backoffClock: Clock,
    override val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    actorSystem: ActorSystem,
    tc: TraceContext,
    ec: ExecutionContext,
    tracer: Tracer,
) extends NamedLogging
    with FlagCloseableAsync
    with RetryProvider.Has {

  val stagingConnection = S3BucketConnection(stagingS3Config, loggerFactory)
  val committedConnection = S3BucketConnection(committedS3Config, loggerFactory)
  val historyMetrics = HistoryMetrics(metricsFactory, currentMigrationId)

  val backfillingCompleteGate: Source[Boolean, Cancellable] =
    Source
      .tick(0.seconds, appConfig.updatesPollingInterval.underlying, ())
      .mapAsync(1)(_ =>
        if (updateHistory.isReady)
          updateHistory.isHistoryBackfilled(currentMigrationId)
        else Future.successful(false)
      )
      .filter(identity)
      .take(1)

  val acsStagingProgress = new AcsSnapshotBulkStoragePersistentProgress(
    acsStagingKvStoreKey,
    firstAcsSnapshotTimestampKvStoreKey,
    kvProvider,
    historyMetrics.BulkStorage.latestAcsSnapshotStaging,
    loggerFactory,
  )
  val acsCommittedProgress = new AcsSnapshotBulkStoragePersistentProgress(
    acsCommittedKvStoreKey,
    firstAcsSnapshotTimestampKvStoreKey,
    kvProvider,
    historyMetrics.BulkStorage.latestAcsSnapshotCommitted,
    loggerFactory,
  )
  private val updatesStagingProgress = new UpdateHistoryBulkStoragePersistentProgress(
    updatesStagingKvStoreKey,
    kvProvider,
    historyMetrics.BulkStorage.latestUpdatesSegmentStaging,
    loggerFactory,
  )
  private val updatesCommittedProgress = new UpdateHistoryBulkStoragePersistentProgress(
    updatesCommittedKvStoreKey,
    kvProvider,
    historyMetrics.BulkStorage.latestUpdatesSegmentCommitted,
    loggerFactory,
  )

  val reader = new BulkStorageReader(
    acsStagingProgress,
    acsCommittedProgress,
    updatesStagingProgress,
    updatesCommittedProgress,
    storageConfig,
    stagingConnection,
    committedConnection,
    loggerFactory,
  )

  val acsStagingWriter = new AcsSnapshotBulkStorageWriterFromDb(
    storageConfig,
    appConfig,
    acsSnapshotStore,
    stagingConnection,
    historyMetrics,
    loggerFactory,
  )
  val acsStaging = new AcsSnapshotBulkStorage(
    "AcsSnapshotBulkStorageStaging",
    acsStagingWriter,
    acsStagingProgress,
    appConfig,
    backfillingCompleteGate,
    loggerFactory,
  )
  val acsCommittedWriter = new AcsSnapshotBulkStorageCommitFromStaging(
    stagingConnection,
    committedConnection,
    reader,
    appConfig,
    loggerFactory,
  )
  val acsCommitted = new AcsSnapshotBulkStorage(
    "AcsSnapshotBulkStorageCommitted",
    acsCommittedWriter,
    acsCommittedProgress,
    appConfig,
    backfillingCompleteGate,
    loggerFactory,
  )
  val updatesStagingWriter = new UpdateHistoryBulkStorageWriterFromDb(
    storageConfig,
    appConfig,
    updateHistory,
    stagingConnection,
    historyMetrics,
    currentMigrationId,
    loggerFactory,
  )
  val updatesStaging = new UpdateHistoryBulkStorage(
    "UpdateHistoryBulkStorageStaging",
    updatesStagingWriter,
    updatesStagingProgress,
    appConfig,
    backfillingCompleteGate,
    loggerFactory,
  )
  val updatesCommittedWriter = new UpdateHistoryBulkStorageCommitFromStaging(
    stagingConnection,
    committedConnection,
    reader,
    appConfig,
    loggerFactory,
  )
  val updatesCommitted = new UpdateHistoryBulkStorage(
    "UpdateHistoryBulkStorageCommitted",
    updatesCommittedWriter,
    updatesCommittedProgress,
    appConfig,
    backfillingCompleteGate,
    loggerFactory,
  )

  private val services =
    Seq[PekkoRetryableService[?]](acsStaging, acsCommitted, updatesStaging, updatesCommitted)
      .map(_.asPekkoRetryingService(automationConfig, backoffClock, retryProvider))

  final override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    services.flatMap(_.closeAsync())
}

object BulkStorage {

  val acsStagingKvStoreKey = "latest_acs_snapshot_in_bulk_storage_staging"
  val acsCommittedKvStoreKey = "latest_acs_snapshot_in_bulk_storage_committed"
  val updatesStagingKvStoreKey = "latest_updates_segment_in_bulk_storage_staging"
  val updatesCommittedKvStoreKey = "latest_updates_segment_in_bulk_storage_committed"
  val firstAcsSnapshotTimestampKvStoreKey = "first_acs_snapshot_timestamp_in_bulk_storage"

  def apply(
      storageConfig: ScanStorageConfig,
      appConfig: BulkStorageConfig,
      acsSnapshotStore: AcsSnapshotStore,
      updateHistory: UpdateHistory,
      currentMigrationId: Long,
      kvProvider: ScanKeyValueProvider,
      metricsFactory: LabeledMetricsFactory,
      automationConfig: AutomationConfig,
      backoffClock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      actorSystem: ActorSystem,
      tc: TraceContext,
      ec: ExecutionContext,
      tracer: Tracer,
  ): BulkStorage = {
    val logger = loggerFactory.getTracedLogger(classOf[BulkStorage])

    (appConfig.staging, appConfig.committed).tupled.fold {
      logger.debug("s3 connection not configured, not dumping to bulk storage")(tc)
      throw Status.FAILED_PRECONDITION
        .withDescription("S3 connection not configured, cannot initialize bulk storage")
        .asRuntimeException()
    } { case (stagingS3Config, committedS3Config) =>
      new BulkStorage(
        storageConfig,
        appConfig,
        stagingS3Config,
        committedS3Config,
        acsSnapshotStore,
        updateHistory,
        currentMigrationId,
        kvProvider,
        metricsFactory,
        automationConfig,
        backoffClock,
        retryProvider,
        loggerFactory,
      )
    }
  }
}

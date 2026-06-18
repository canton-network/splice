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
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, S3Config}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, S3BucketConnection, UpdateHistory}

import scala.concurrent.ExecutionContext
import cats.implicits.*
import org.lfdecentralizedtrust.splice.RetryableService
import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorage.{
  acsKvStoreKey,
  updatesKvStoreKey,
}

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

  def getReader: BulkStorageReader = reader

  val stagingConnection = S3BucketConnection(stagingS3Config, loggerFactory)
  val committedConnection = S3BucketConnection(committedS3Config, loggerFactory)
  val historyMetrics = HistoryMetrics(metricsFactory, currentMigrationId)

  val acsStagingWriter = new AcsSnapshotBulkStorageWriterFromDb(
    storageConfig,
    appConfig,
    acsSnapshotStore,
    stagingConnection,
    historyMetrics,
    loggerFactory,
  )
  val acsStaging = new AcsSnapshotBulkStorage(
    "ACS Snapshot Bulk Storage (Staging)",
    acsStagingWriter,
    new AcsSnapshotBulkStoragePersistentProgress(
      acsKvStoreKey,
      kvProvider,
      historyMetrics.BulkStorage.latestAcsSnapshotStaging,
      loggerFactory,
    ),
    appConfig,
    acsSnapshotStore,
    updateHistory,
    loggerFactory,
  )
  val acsCommittedWriter = new AcsSnapshotBulkStorageCommitFromStaging(
    stagingConnection,
    committedConnection,
    getReader,
    loggerFactory,
  )
  val acsCommitted = new AcsSnapshotBulkStorage(
    "ACS Snapshot Bulk Storage (Committed)",
    acsCommittedWriter,
    new AcsSnapshotBulkStoragePersistentProgress(
      s"$acsKvStoreKey-committed", // FIXME
      kvProvider,
      historyMetrics.BulkStorage.latestAcsSnapshotCommitted,
      loggerFactory,
    ),
    appConfig,
    acsSnapshotStore,
    updateHistory,
    loggerFactory,
  )
  val updatesStaging = new UpdateHistoryBulkStorageWriterFromDb(
    storageConfig,
    appConfig,
    updateHistory,
    stagingConnection,
    historyMetrics,
    loggerFactory,
  )
  val updates = new UpdateHistoryBulkStorage(
    "Update History Bulk Storage (Staging)",
    updatesStaging,
    new UpdateHistoryBulkStoragePersistentProgress(
      updatesKvStoreKey,
      kvProvider,
      historyMetrics.BulkStorage.latestUpdatesSegmentStaging,
      loggerFactory,
    ),
    storageConfig,
    appConfig,
    updateHistory,
    currentMigrationId,
    loggerFactory,
  )
  val reader = new BulkStorageReader(
    acsStaging,
    acsCommitted,
    updates,
    storageConfig,
    stagingConnection,
    committedConnection,
    loggerFactory,
  )

  final override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    Seq[RetryableService[?]](acsStaging, acsCommitted, updates)
      .flatMap(
        _.asRetryableService(automationConfig, backoffClock, retryProvider).closeAsync()
      )
  }
}

object BulkStorage {

  val acsKvStoreKey = "latest_acs_snapshot_in_bulk_storage"
  val updatesKvStoreKey = "latest_updates_segment_in_bulk_storage"

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

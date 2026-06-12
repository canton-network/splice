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
import org.lfdecentralizedtrust.splice.PekkoRetryingService
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, S3BucketConnection, UpdateHistory}

import scala.concurrent.ExecutionContext

class BulkStorage(
    val reader: BulkStorageReader,
    services: Seq[PekkoRetryingService[?]],
    override protected val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
) extends NamedLogging
    with FlagCloseableAsync
    with RetryProvider.Has {

  final override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    services.flatMap(_.closeAsync())
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

    appConfig.s3.fold {
      logger.debug("s3 connection not configured, not dumping to bulk storage")(tc)
      throw Status.FAILED_PRECONDITION
        .withDescription("S3 connection not configured, cannot initialize bulk storage")
        .asRuntimeException()
    } { s3Config =>
      val s3Connection = S3BucketConnection(s3Config, loggerFactory)
      val historyMetrics = HistoryMetrics(metricsFactory, currentMigrationId)

      val acsStaging = new AcsSnapshotBulkStorageWriterFromDb(
        storageConfig,
        appConfig,
        acsSnapshotStore,
        s3Connection,
        historyMetrics,
        loggerFactory,
      )
      val acs = new AcsSnapshotBulkStorage(
        "ACS Snapshot Bulk Storage (Staging)",
        acsStaging,
        new AcsSnapshotBulkStoragePersistentProgress(
          acsKvStoreKey,
          kvProvider,
          historyMetrics.BulkStorage.latestAcsSnapshot,
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
        s3Connection,
        historyMetrics,
        loggerFactory,
      )
      val updates = new UpdateHistoryBulkStorage(
        "Update History Bulk Storage (Staging)",
        updatesStaging,
        new UpdateHistoryBulkStoragePersistentProgress(
          updatesKvStoreKey,
          kvProvider,
          historyMetrics.BulkStorage.latestUpdatesSegment,
          loggerFactory,
        ),
        storageConfig,
        appConfig,
        updateHistory,
        currentMigrationId,
        loggerFactory,
      )
      val reader = new BulkStorageReader(
        acsSnapshotBulkStorage = acs,
        updateHistoryBulkStorage = updates,
        storageConfig,
        s3Connection,
        loggerFactory,
      )

      new BulkStorage(
        reader = reader,
        services = Seq(
          acs.asRetryableService(automationConfig, backoffClock, retryProvider),
          updates.asRetryableService(automationConfig, backoffClock, retryProvider),
        ),
        retryProvider = retryProvider,
        loggerFactory = loggerFactory,
      )
    }
  }
}

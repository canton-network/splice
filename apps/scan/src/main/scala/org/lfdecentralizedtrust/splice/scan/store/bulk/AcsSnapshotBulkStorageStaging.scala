// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}
import org.lfdecentralizedtrust.splice.store.{
  HardLimit,
  HistoryMetrics,
  Limit,
  S3BucketConnection,
  TimestampWithMigrationId,
  UpdateHistory,
}

import scala.concurrent.{ExecutionContext, Future}
import cats.data.OptionT
import cats.implicits.*
import com.daml.metrics.api.MetricHandle
import com.digitalasset.canton.data.CantonTimestamp
import io.grpc.Status
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.lfdecentralizedtrust.splice.scan.store.bulk.AcsSnapshotBulkStorage.AcsSnapshotObjects

class AcsSnapshotBulkStorageStaging(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    acsSnapshotStore: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    s3Connection: S3BucketConnection,
    kvProvider: ScanKeyValueProvider,
    historyMetrics: HistoryMetrics,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends AcsSnapshotBulkStorage(
      appConfig,
      acsSnapshotStore,
      updateHistory,
      kvProvider,
      loggerFactory,
    ) {

  override val description = "ACS Snapshot Bulk Storage (Staging)"
  override val kvStoreKey = "latest_acs_snapshot_in_staging_bulk_storage"

  protected val processedTimestampMetric: MetricHandle.Gauge[CantonTimestamp] =
    historyMetrics.BulkStorage.latestAcsSnapshotStaging

  override protected def getNextSnapshotTimestampAfter(
      last: TimestampWithMigrationId
  )(implicit tc: TraceContext): Future[Option[TimestampWithMigrationId]] =
    OptionT(acsSnapshotStore.lookupSnapshotAfter(last.migrationId, last.timestamp))
      .map(snapshot => TimestampWithMigrationId(snapshot.snapshotRecordTime, snapshot.migrationId))
      .value

  override protected def shouldProcessSnapshotAt(ts: TimestampWithMigrationId)(implicit
      tc: TraceContext
  ): Boolean = {
    val ret = storageConfig.shouldDumpSnapshotToBulkStorage(ts.timestamp)
    if (ret) {
      logger.debug(s"Dumping snapshot at timestamp ${ts.timestamp} to bulk storage")
    } else {
      logger.info(
        s"Skipping snapshot at timestamp ${ts.timestamp} for bulk storage, not required per the configured period of ${storageConfig.bulkAcsSnapshotPeriodHours}"
      )
    }
    ret
  }

  override protected def processSnapshotAt(ts: TimestampWithMigrationId)(implicit
      tc: TraceContext
  ): Flow[TimestampWithMigrationId, TimestampWithMigrationId, NotUsed] = {
    SingleAcsSnapshotBulkStorage
      .asFlow(
        storageConfig,
        appConfig,
        acsSnapshotStore,
        s3Connection,
        historyMetrics,
        loggerFactory,
      )
      .map(keys => {
        logger.debug(
          s"Successfully dumped snapshot from migration ${ts.migrationId}, timestamp ${ts.timestamp} to bulk storage, with object keys: $keys"
        )
        ts
      })
  }
}

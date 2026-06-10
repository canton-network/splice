package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.MetricHandle
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, S3BucketConnection, TimestampWithMigrationId, UpdateHistory}

import scala.concurrent.{ExecutionContext, Future}

class AcsSnapshotBulkStorageCommitted(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    acsSnapshotStore: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    stagingS3Connection: S3BucketConnection,
    committedS3Connection: S3BucketConnection,
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
  override val description = "ACS Snapshot Bulk Storage (Committed)"
  override val kvStoreKey = "latest_acs_snapshot_in_committed_bulk_storage"
  override protected val processedTimestampMetric: MetricHandle.Gauge[CantonTimestamp] =
    historyMetrics.BulkStorage.latestAcsSnapshotCommitted

  override protected def getNextSnapshotTimestampAfter(last: TimestampWithMigrationId)(implicit tc: TraceContext): Future[Option[TimestampWithMigrationId]] = ???

  override protected def shouldProcessSnapshotAt(ts: TimestampWithMigrationId)(implicit tc: TraceContext): Boolean = ???

  override protected def processSnapshotAt(ts: TimestampWithMigrationId)(implicit tc: TraceContext): Flow[TimestampWithMigrationId, TimestampWithMigrationId, NotUsed] = ???
}

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.MetricHandle
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, S3BucketConnection, UpdateHistory}

import scala.concurrent.ExecutionContext

class UpdateHistoryBulkStorageCommitted(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    updateHistory: UpdateHistory,
    kvProvider: ScanKeyValueProvider,
    currentMigrationId: Long,
    stagingS3Connection: S3BucketConnection,
    committedS3Connection: S3BucketConnection,
    historyMetrics: HistoryMetrics,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends UpdateHistoryBulkStorage(
      storageConfig,
      appConfig,
      updateHistory,
      kvProvider,
      currentMigrationId,
      loggerFactory,
    ) {

  override protected val description: String = "Update History Bulk Storage (Committed)"
  override protected val kvStoreKey: String = "latest_updates_segment_in_committed_bulk_storage"
  override val processedSegmentMetric: MetricHandle.Gauge[CantonTimestamp] =
    historyMetrics.BulkStorage.latestUpdatesSegmentCommitted

  override protected def processSegment(
      segment: UpdatesSegment
  )(implicit tc: TraceContext): Flow[UpdatesSegment, UpdatesSegment, NotUsed] = ???

}

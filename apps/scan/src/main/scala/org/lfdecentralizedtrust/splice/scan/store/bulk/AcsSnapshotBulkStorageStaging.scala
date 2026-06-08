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
      loggerFactory,
    ) {
  override protected def readLatestProcessedSnapshotTimestamp(implicit
      tc: TraceContext
  ): Future[Option[TimestampWithMigrationId]] =
    kvProvider.getLatestAcsSnapshotInBulkStorage().value

  override protected def persistLatestProcessedSnapshotTimestamp(ts: TimestampWithMigrationId)(
      implicit tc: TraceContext
  ): Future[Unit] =
    kvProvider
      .setLatestAcsSnapshotsInBulkStorage(ts)
      .map(_ => {
        logger.info(
          s"Successfully completed dumping snapshots from migration ${ts.migrationId}, timestamp ${ts.timestamp}"
        )
      })

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

  override protected def updateMetric(ts: TimestampWithMigrationId): Unit = {
    historyMetrics.BulkStorage.latestAcsSnapshot.updateValue(ts.timestamp)
  }

  // FIXME: consider moving this up to AcsSnapshotBulkStorage, we'll probably need something similar for both staging and completed buckets
  def getAcsSnapshotAtOrBefore(
      atOrBeforeTimestamp: CantonTimestamp
  )(implicit tc: TraceContext): Future[AcsSnapshotObjects] = {

    for {
      snapshotTs <- kvProvider
        .getLatestAcsSnapshotInBulkStorage()
        .value
        .map {
          case None =>
            throw Status.NOT_FOUND
              .withDescription("no snapshot in bulk storage yet")
              .asRuntimeException()
          case Some(ts) if ts.timestamp < atOrBeforeTimestamp =>
            logger.trace(
              s"Latest snapshot in bulk storage is at ${ts.timestamp}, which is before the requested timestamp ${atOrBeforeTimestamp}, returning that one"
            )
            ts.timestamp
          case Some(ts) => storageConfig.computeBulkSnapshotTimeAtOrBefore(atOrBeforeTimestamp)
        }
      prefix = storageConfig.findSegmentFolderPrefixByStartTimestamp(snapshotTs)
      objects <- s3Connection
        // A single object currently holds ~700K contracts, we apply a Limit just for safety,
        // but we don't expect to get anywhere near 1000 such objects in the foreseeable future
        // (hence the HardLimit, just as a safety precaution).
        .listObjects(
          prefix,
          _.matches(".*ACS_\\d+\\.zstd"),
          HardLimit.tryCreate(Limit.DefaultMaxPageSize),
        )
      objectsWithChecksums <- s3Connection.getChecksums(objects)
    } yield {
      if (objects.isEmpty) {
        throw Status.NOT_FOUND
          .withDescription(
            s"No snapshot objects found in bulk storage at expected timestamp at or before $atOrBeforeTimestamp, this may be because the timestamp is before network genesis"
          )
          .asRuntimeException()
      }
      logger.trace(
        s"Found snapshot in bulk storage at timestamp $snapshotTs, with objects: ${objects.mkString(", ")}"
      )
      AcsSnapshotObjects(snapshotTs, objectsWithChecksums)
    }
  }

}

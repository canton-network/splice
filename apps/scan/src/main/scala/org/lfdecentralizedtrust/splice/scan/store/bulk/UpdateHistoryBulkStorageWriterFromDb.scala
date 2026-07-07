// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.store.{
  HistoryMetrics,
  PageLimit,
  S3BucketConnection,
  TimestampWithMigrationId,
  UpdateHistory,
}

import scala.concurrent.{ExecutionContext, Future}

class UpdateHistoryBulkStorageWriterFromDb(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    updateHistory: UpdateHistory,
    s3Connection: S3BucketConnection,
    historyMetrics: HistoryMetrics,
    currentMigrationId: Long,
    val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends UpdateHistoryBulkStorageWriter
    with NamedLogging
    with Spanning {

  private def getSegmentEndAfter(
      ts: TimestampWithMigrationId
  )(implicit tc: TraceContext): Future[TimestampWithMigrationId] = {
    val endTs = storageConfig.computeBulkSnapshotTimeAfter(ts.timestamp)
    for {
      endMigration <-
        if (ts.migrationId < currentMigrationId) {
          getMigrationIdForAcsSnapshot(endTs)
        } else {
          /* When dumping updates for the current migration ID, we always assume that this migration ID
           continues beyond the segment, i.e. that the current migration ID is also the migration ID at
           the end of the segment. If this does not hold, and a migration happens before the end of the
           segment, then:
           a. this app will stop ingesting updates before the end of the segment, hence this segment will not be considered completed
           b. eventually, the app will be restarted with the new migration ID, and this segment will be retried in the new app,
              where (ts.migrationId == currentMigrationId) will no longer hold.
           */
          Future.successful(currentMigrationId)
        }
    } yield {
      TimestampWithMigrationId(endTs, endMigration)
    }
  }

  private def getMigrationIdForAcsSnapshot(
      snapshotTimestamp: CantonTimestamp
  )(implicit tc: TraceContext): Future[Long] = {
    /* The migration ID in ACS snapshots is always the lowest migration that has updates with a later record time,
       because we only create an ACS snapshot in an app if it has seen updates with a later timestamp.
       If no such updates exist, then we assume that the current migration will be that of the snapshot. If a migration
       happens before that time, then the app will restart with a higher migration, and therefore also restart dumping
       this segment.
     */
    updateHistory
      .getLowestMigrationForRecordTime(snapshotTimestamp)
      .map(_.getOrElse(currentMigrationId))
  }

  /** Gets the very first updates segment for this network after genesis
    * May return None if unknown yet. This could happen if no updates have been ingested,
    * so we do not know the genesis record time yet. The caller should then schedule a retry.
    */
  private def getFirstSegmentFromGenesis(implicit
      tc: TraceContext
  ): Future[Option[UpdatesSegment]] =
    for {
      firstUpdate <- updateHistory.getUpdatesWithoutImportUpdates(None, PageLimit.tryCreate(1))
      segmentEnd <- firstUpdate.headOption match {
        case None => Future.successful(None)
        case Some(first) =>
          getSegmentEndAfter(
            TimestampWithMigrationId(first.update.update.recordTime, first.migrationId)
          ).map(Some(_))
      }
    } yield {
      segmentEnd.map(UpdatesSegment(TimestampWithMigrationId(CantonTimestamp.MinValue, 0), _))
    }

  override def getNextSegmentAfter(
      afterO: Option[UpdatesSegment]
  )(implicit tc: TraceContext): Future[Option[UpdatesSegment]] =
    afterO match {
      case Some(previous) =>
        getSegmentEndAfter(previous.toTimestamp).map(end =>
          Some(UpdatesSegment(previous.toTimestamp, end))
        )
      case None => getFirstSegmentFromGenesis
    }

  override def processSegmentsFlow(implicit
      tc: TraceContext
  ): Flow[UpdatesSegment, UpdatesSegment, NotUsed] = {
    UpdateHistorySegmentBulkStorage
      .asFlow(
        storageConfig,
        appConfig,
        updateHistory,
        s3Connection,
        historyMetrics,
        loggerFactory,
      )
      .map { case (segment, keys) =>
        logger.debug(
          s"Successfully dumped updates segment $segment to bulk storage, with object keys: $keys"
        )
        segment
      }
  }

}

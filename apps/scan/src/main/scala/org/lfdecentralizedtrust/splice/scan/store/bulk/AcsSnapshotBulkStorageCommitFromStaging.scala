package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.lfdecentralizedtrust.splice.scan.store.bulk.AcsSnapshotBulkStorage.AcsSnapshotObjects
import org.lfdecentralizedtrust.splice.store.TimestampWithMigrationId

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class AcsSnapshotBulkStorageCommitFromStaging(
    bulkReader: BulkStorageReader,
    val loggerFactory: NamedLoggerFactory,
) extends AcsSnapshotBulkStorageWriter
    with NamedLogging {
  override def getNextSnapshotTimestampAfter(
      last: TimestampWithMigrationId
  )(implicit tc: TraceContext): Future[Option[TimestampWithMigrationId]] = {
    bulkReader
      .getTimestampOfStagingAcsSnapshotAfter(last.timestamp)
      .map(
        _.map(ts => TimestampWithMigrationId(ts, -1L))
      ) // migration IDs are not used in bulk storage, so we set it to a fake -1
  }

  override def shouldProcessSnapshotAt(ts: TimestampWithMigrationId)(implicit
      tc: TraceContext
  ): Boolean = true // all objects in staging should be moved to committed

  private def checkBftForObjects(
      objects: AcsSnapshotObjects
  ): Future[Boolean] = ???

  private def waitForBftAgreement(implicit
      ec: ExecutionContext
  ): Flow[AcsSnapshotObjects, AcsSnapshotObjects, NotUsed] = {
    Flow[AcsSnapshotObjects].mapAsync(parallelism = 1) { obj =>
      Source
        .repeat(obj)
        .mapAsync(parallelism = 1)(obj => checkBftForObjects(obj).map(result => (obj, result)))
        .flatMapConcat {
          case (obj, true) =>
            logger.debug(
              s"BFT agreement reached for snapshot at timestamp ${obj.timestamp}. Proceeding with commit."
            )
            Source.single((obj, true))

          case (obj, false) =>
            logger.debug(
              s"BFT agreement not yet reached for snapshot at timestamp ${obj.timestamp}. Will retry after delay."
            )
            Source.single((obj, false)).delay(30.seconds)
        }
        .takeWhile({ case (_, bftReached) => !bftReached }, inclusive = true)
        .runWith(Sink.last)
        .map { case (obj, _) => obj }
    }
  }

  private def copyToCommitted(implicit
      ec: ExecutionContext
  ): Flow[AcsSnapshotObjects, TimestampWithMigrationId, NotUsed] = ???
  // Copy object from staging to committed, skipping objects already committed (might happen e.g. due to restarts) because we don't have delete/rewrite permissions on the committed bucket.

  private def deleteFromStaging(implicit
      ec: ExecutionContext
  ): Flow[TimestampWithMigrationId, TimestampWithMigrationId, NotUsed] = ???
  // Delete objects from staging (after they have been copied to committed)

  override def processSnapshot(implicit
      tc: TraceContext
  ): Flow[TimestampWithMigrationId, TimestampWithMigrationId, NotUsed] = {
    Flow[TimestampWithMigrationId]
      .mapAsync(parallelism = 1) { ts =>
        bulkReader.getStagingObjectsForAcsSnapshotAt(ts.timestamp)
      }
      .via(waitForBftAgreement)
      .via(copyToCommitted)
      .via(deleteFromStaging)
  }
}

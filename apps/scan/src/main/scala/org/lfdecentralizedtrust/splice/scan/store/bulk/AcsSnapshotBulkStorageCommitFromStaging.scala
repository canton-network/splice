// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.lfdecentralizedtrust.splice.scan.store.bulk.AcsSnapshotBulkStorage.AcsSnapshotObjects
import org.lfdecentralizedtrust.splice.store.{S3BucketConnection, TimestampWithMigrationId}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class AcsSnapshotBulkStorageCommitFromStaging(
    stagingS3Connection: S3BucketConnection,
    committedS3Connection: S3BucketConnection,
    getBulkReader: => BulkStorageReader,
    val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends AcsSnapshotBulkStorageWriter
    with NamedLogging {
  override def getNextSnapshotTimestampAfter(
      last: TimestampWithMigrationId
  )(implicit tc: TraceContext): Future[Option[TimestampWithMigrationId]] = {
    getBulkReader
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
  )(implicit tc: TraceContext): Future[Boolean] = {
    logger.debug(
      s"Checking BFT agreement for objects: ${objects.objects.map(_.key).mkString(", ")} (for snapshot at timestamp ${objects.timestamp})"
    )
    Future.successful(true)
  }
  // TODO(#XXXX): implement the BFT check

  private def waitForBftAgreement(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      actorSystem: ActorSystem,
  ): Flow[
    (TimestampWithMigrationId, AcsSnapshotObjects),
    (TimestampWithMigrationId, AcsSnapshotObjects),
    NotUsed,
  ] = {
    Flow[(TimestampWithMigrationId, AcsSnapshotObjects)].mapAsync(parallelism = 1) {
      case (ts, obj) =>
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
          .map { case (obj, _) => (ts, obj) }
    }
  }

  private def copyObjectToCommitted(
      obj: S3BucketConnection.ObjectKeyAndChecksum
  )(implicit tc: TraceContext): Future[Unit] = {
    committedS3Connection.doesObjectExist(obj.key).flatMap {
      case true =>
        logger.debug(
          s"Object ${obj.key} already exists in committed storage, this may happen e.g. if we restarted before copying all objects and deleting them from staging. Skipping copy"
        )
        Future.unit
      case false =>
        logger.debug(s"Copying object ${obj.key} from staging to committed storage")
        stagingS3Connection.copyObject(obj.key, committedS3Connection.bucketName, obj.key)
    }
  }

  private def copyToCommitted(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Flow[
    (TimestampWithMigrationId, AcsSnapshotObjects),
    (TimestampWithMigrationId, AcsSnapshotObjects),
    NotUsed,
  ] =
    Flow[(TimestampWithMigrationId, AcsSnapshotObjects)]
      .mapAsync(parallelism = 1) { case (ts, objs) =>
        Future.sequence(objs.objects.map(copyObjectToCommitted)).map(_ => (ts, objs))
      }

  private def deleteFromStaging(implicit
      ec: ExecutionContext
  ): Flow[(TimestampWithMigrationId, AcsSnapshotObjects), TimestampWithMigrationId, NotUsed] =
    Flow[(TimestampWithMigrationId, AcsSnapshotObjects)]
      .mapAsync(parallelism = 1) { case (ts, objs) =>
        Future
          .sequence(
            objs.objects.map(obj => stagingS3Connection.deleteObject(obj.key))
          )
          .map(_ => ts)
      }

  override def processSnapshotsFlow(implicit
      tc: TraceContext,
      actorSystem: ActorSystem,
  ): Flow[TimestampWithMigrationId, TimestampWithMigrationId, NotUsed] = {
    Flow[TimestampWithMigrationId]
      .mapAsync(parallelism = 1) { ts =>
        getBulkReader.getStagingObjectsForAcsSnapshotAt(ts.timestamp).map(objects => (ts, objects))
      }
      .via(waitForBftAgreement)
      .via(copyToCommitted)
      .via(deleteFromStaging)
  }
}

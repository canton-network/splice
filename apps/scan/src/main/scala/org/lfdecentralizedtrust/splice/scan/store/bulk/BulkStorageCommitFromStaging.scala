// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.lfdecentralizedtrust.splice.scan.config.BulkStorageConfig
import org.lfdecentralizedtrust.splice.store.S3BucketConnection
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum

import scala.concurrent.{ExecutionContext, Future}

// TODO(#5884): review parallelism here. We use parallelism = 1 all over, but unsure whether that's actually necessary.

class BulkStorageCommitFromStaging[T](
    stagingS3Connection: S3BucketConnection,
    committedS3Connection: S3BucketConnection,
    getObjects: T => Future[Seq[ObjectKeyAndChecksum]],
    appConfig: BulkStorageConfig,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    tc: TraceContext,
    ec: ExecutionContext,
    actorSystem: ActorSystem,
) extends NamedLogging {
  private def checkBftForObjects(
      objects: Seq[ObjectKeyAndChecksum]
  ): Future[Boolean] = {
    logger.debug(
      s"Checking BFT agreement for objects: ${objects.map(_.key).mkString(", ")}"
    )
    Future.successful(true)
  }
  // TODO(#5884): implement the BFT check

  private def waitForBftAgreement: Flow[
    (T, Seq[ObjectKeyAndChecksum]),
    (T, Seq[ObjectKeyAndChecksum]),
    NotUsed,
  ] = {
    Flow[(T, Seq[ObjectKeyAndChecksum])].mapAsync(parallelism = 1) { case (t, obj) =>
      Source
        .repeat(obj)
        .mapAsync(parallelism = 1)(obj => checkBftForObjects(obj).map(result => (obj, result)))
        .flatMapConcat {
          case (obj, true) =>
            logger.debug(
              s"BFT agreement reached for the objects of $t. Proceeding with commit."
            )
            Source.single((obj, true))

          case (obj, false) =>
            logger.debug(
              s"BFT agreement not yet reached for the objects at $t. Will retry after delay."
            )
            Source.single((obj, false)).delay(appConfig.bftRetryInterval.underlying)
        }
        .takeWhile({ case (_, bftReached) => !bftReached }, inclusive = true)
        .runWith(Sink.last)
        .map { case (obj, _) => (t, obj) }
    }
  }

  private def copyObjectToCommitted(
      stagingS3Connection: S3BucketConnection,
      committedS3Connection: S3BucketConnection,
  )(
      obj: S3BucketConnection.ObjectKeyAndChecksum
  ): Future[Unit] = {
    committedS3Connection.doesObjectExist(obj.key).flatMap {
      case true =>
        logger.debug(
          s"Object ${obj.key} already exists in committed storage, this may happen e.g. if we restarted before copying all objects and deleting them from staging. Skipping copy"
        )
        Future.unit
      case false =>
        logger.debug(s"Copying object ${obj.key} from staging to committed storage")
        committedS3Connection.copyObject(stagingS3Connection.bucketName, obj.key)
    }
  }

  private def copyToCommitted: Flow[
    (T, Seq[ObjectKeyAndChecksum]),
    (T, Seq[ObjectKeyAndChecksum]),
    NotUsed,
  ] =
    Flow[(T, Seq[ObjectKeyAndChecksum])]
      .mapAsync(parallelism = 1) { case (ts, objs) =>
        logger.debug(
          s"Copying ${objs.size} objects from staging to committed storage for timestamp $ts"
        )
        Future
          .sequence(objs.map(copyObjectToCommitted(stagingS3Connection, committedS3Connection)))
          .map(_ => (ts, objs))
      }

  private def deleteFromStaging: Flow[
    (T, Seq[ObjectKeyAndChecksum]),
    T,
    NotUsed,
  ] =
    Flow[(T, Seq[ObjectKeyAndChecksum])]
      .mapAsync(parallelism = 1) { case (ts, objs) =>
        logger.debug(
          s"Deleting ${objs.size} objects from staging storage for timestamp $ts"
        )
        Future
          .sequence(
            objs.map(obj => {
              logger.debug(s"Deleting object ${obj.key} from staging storage")
              stagingS3Connection
                .deleteObject(obj.key)
                .map(_ => logger.debug(s"Deleted object ${obj.key} from staging storage"))
            })
          )
          .map(_ => ts)
      }
      .wireTap(ts => logger.debug(s"Successfully deleted objects from staging for timestamp $ts"))

  def getFlow: Flow[T, T, NotUsed] =
    Flow[T]
      .mapAsync(parallelism = 1)(ts => getObjects(ts).map((ts, _)))
      .via(waitForBftAgreement)
      .via(copyToCommitted)
      .via(deleteFromStaging)
      .wireTap(ts => logger.debug(s"Successfully committed objects for timestamp $ts"))
}

object BulkStorageCommitFromStaging {
  def apply[T](
      stagingS3Connection: S3BucketConnection,
      committedS3Connection: S3BucketConnection,
      getStagingObjects: T => Future[Seq[ObjectKeyAndChecksum]],
      appConfig: BulkStorageConfig,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): Flow[T, T, NotUsed] = {
    new BulkStorageCommitFromStaging[T](
      stagingS3Connection,
      committedS3Connection,
      getStagingObjects,
      appConfig,
      loggerFactory,
    ).getFlow
  }
}

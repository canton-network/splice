package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

trait BulkStorageCommitFromStaging[T] extends NamedLogging {
  def checkBftForObjects(
      objects: Seq[ObjectKeyAndChecksum]
  )(implicit tc: TraceContext): Future[Boolean] = {
    logger.debug(
      s"Checking BFT agreement for objects: ${objects.map(_.key).mkString(", ")}"
    )
    Future.successful(true)
  }
  // TODO(#XXXX): implement the BFT check

  def waitForBftAgreement(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      actorSystem: ActorSystem,
  ): Flow[
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
            Source.single((obj, false)).delay(30.seconds)
          // FIXME: take the delay from the config
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
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
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

  def copyToCommitted(
      stagingS3Connection: S3BucketConnection,
      committedS3Connection: S3BucketConnection,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Flow[
    (T, Seq[ObjectKeyAndChecksum]),
    (T, Seq[ObjectKeyAndChecksum]),
    NotUsed,
  ] =
    Flow[(T, Seq[ObjectKeyAndChecksum])]
      .mapAsync(parallelism = 1) { case (ts, objs) =>
        Future
          .sequence(objs.map(copyObjectToCommitted(stagingS3Connection, committedS3Connection)))
          .map(_ => (ts, objs))
      }

  def deleteFromStaging(
      stagingS3Connection: S3BucketConnection
  )(implicit ec: ExecutionContext): Flow[
    (T, Seq[ObjectKeyAndChecksum]),
    T,
    NotUsed,
  ] =
    Flow[(T, Seq[ObjectKeyAndChecksum])]
      .mapAsync(parallelism = 1) { case (ts, objs) =>
        Future
          .sequence(
            objs.map(obj => stagingS3Connection.deleteObject(obj.key))
          )
          .map(_ => ts)
      }

  def processFlow(
      stagingS3Connection: S3BucketConnection,
      committedS3Connection: S3BucketConnection,
      getObjects: T => Future[Seq[ObjectKeyAndChecksum]],
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): Flow[T, T, NotUsed] =
    Flow[T]
      .mapAsync(parallelism = 1)(ts => getObjects(ts).map((ts, _)))
      .via(waitForBftAgreement)
      .via(copyToCommitted(stagingS3Connection, committedS3Connection))
      .via(deleteFromStaging(stagingS3Connection))

}

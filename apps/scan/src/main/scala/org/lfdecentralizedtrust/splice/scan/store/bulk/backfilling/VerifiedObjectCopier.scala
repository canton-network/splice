// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import cats.implicits.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.store.S3BucketConnection
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum

import java.security.MessageDigest
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

trait ObjectCopier {
  def copy(objects: Seq[ObjectKeyAndChecksum])(implicit tc: TraceContext): Future[Unit]
}

class VerifiedObjectCopier(
    source: PeerObjectSource,
    anyPeer: Seq[Uri] => Uri,
    staging: S3BucketConnection,
    committed: S3BucketConnection,
    parallelism: Int,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends ObjectCopier
    with NamedLogging {

  import VerifiedObjectCopier.*

  override def copy(objects: Seq[ObjectKeyAndChecksum])(implicit tc: TraceContext): Future[Unit] =
    Source(objects.toList)
      .mapAsync(math.max(1, parallelism))(obj => copyOne(obj))
      .runWith(Sink.ignore)
      .map(_ => ())

  private def copyOne(obj: ObjectKeyAndChecksum)(implicit tc: TraceContext): Future[Unit] =
    alreadyPresent(obj).flatMap { present =>
      if (present) {
        logger.debug(s"Object ${obj.key} is already present with the expected checksum, skipping")
        Future.unit
      } else copyFromAnyPeer(obj)
    }

  private def alreadyPresent(obj: ObjectKeyAndChecksum)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    (hasObject(staging, obj), hasObject(committed, obj)).mapN(_ || _)

  private def hasObject(bucket: S3BucketConnection, obj: ObjectKeyAndChecksum)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    bucket
      .getChecksums(Seq(obj.key))(ec, mat.system, tc)
      .map(_.exists(_.checksum == obj.checksum))

  private def copyFromAnyPeer(obj: ObjectKeyAndChecksum)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    def tryRemaining(remaining: Seq[Uri], failures: List[String]): Future[Unit] =
      if (remaining.isEmpty) Future.failed(new CopyFailed(obj.key, failures.reverse))
      else {
        val peer = anyPeer(remaining)
        def tryOthers(e: Throwable) =
          tryRemaining(remaining.filterNot(_ == peer), s"$peer: ${e.getMessage}" :: failures)
        copyFromPeer(obj, peer).recoverWith {
          case e: StagingWriteFailed =>
            Future.failed(e)
          case e: ChecksumMismatch =>
            logger.warn(e.getMessage)
            tryOthers(e)
          case e =>
            logger.info(
              s"Failed to obtain object ${obj.key} from peer $peer, trying the next: ${e.getMessage}"
            )
            tryOthers(e)
        }
      }
    source.peers.flatMap(peers => tryRemaining(peers, Nil))
  }

  private def copyFromPeer(obj: ObjectKeyAndChecksum, peer: Uri)(implicit
      tc: TraceContext
  ): Future[Unit] =
    source.open(peer, obj.key).flatMap { download =>
      val writer = staging.newAppendWriteObject(obj.key)
      val digest = MessageDigest.getInstance("SHA-256")
      download
        .groupedWeighted(uploadPartSize.toLong)(_.size.toLong)
        .map(chunks => chunks.foldLeft(ByteString.empty)(_ ++ _).asByteBuffer)
        .mapAsync(1) { part =>
          // Update the digest here, in part order; never inside the upload Future.
          digest.update(part.duplicate())
          writeToStaging(obj.key)(writer.upload(writer.prepareUploadNext(part), part))
        }
        .runWith(Sink.ignore)
        .flatMap { _ =>
          val actual = Base64.getEncoder.encodeToString(digest.digest())
          if (actual == obj.checksum) writeToStaging(obj.key)(writer.finish())
          else
            Future.failed(
              new ChecksumMismatch(obj.key, peer, expected = obj.checksum, actual = actual)
            )
        }
        .recoverWith { case e =>
          discardFromStaging(writer).transformWith(_ => Future.failed(e))
        }
    }

  private def writeToStaging[T](key: String)(write: => Future[T]): Future[T] =
    Future.delegate(write).recoverWith { case e =>
      Future.failed(new StagingWriteFailed(key, e))
    }

  private def discardFromStaging(writer: S3BucketConnection#AppendWriteObject): Future[Unit] =
    writer
      .abort()
      .transformWith(_ => staging.deleteObject(writer.key))
      .transformWith(_ => Future.unit)
}

object VerifiedObjectCopier {
  val uploadPartSize: Int = 8 * 1024 * 1024

  val randomPeer: Seq[Uri] => Uri = peers => peers(Random.nextInt(peers.size))

  final class ChecksumMismatch(key: String, peer: Uri, expected: String, actual: String)
      extends RuntimeException(
        s"Checksum mismatch for object $key from peer $peer: expected $expected, got $actual"
      )

  final class StagingWriteFailed(key: String, cause: Throwable)
      extends RuntimeException(
        s"Could not write object $key to staging: ${cause.getMessage}",
        cause,
      )

  final class CopyFailed(key: String, failures: Seq[String])
      extends RuntimeException(
        s"Could not copy object $key from any peer: ${failures.mkString("; ")}"
      )
}

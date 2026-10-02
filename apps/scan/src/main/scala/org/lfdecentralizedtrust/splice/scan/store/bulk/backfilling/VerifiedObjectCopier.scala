// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
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
    peerOrder: Seq[String] => Seq[String],
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
    alreadyPresent(obj).flatMap {
      case true =>
        logger.debug(s"Object ${obj.key} is already present with the expected checksum, skipping")
        Future.unit
      case false =>
        for {
          peers <- source.peers
          _ <- copyFromAnyPeer(obj, peerOrder(peers), Nil)
        } yield ()
    }

  private def alreadyPresent(obj: ObjectKeyAndChecksum)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    def has(bucket: S3BucketConnection) =
      bucket
        .getChecksums(Seq(obj.key))(ec, mat.system, tc)
        .map(_.exists(_.checksum == obj.checksum))
    has(staging).flatMap {
      case true => Future.successful(true)
      case false => has(committed)
    }
  }

  private def copyFromAnyPeer(
      obj: ObjectKeyAndChecksum,
      peers: Seq[String],
      failures: List[String],
  )(implicit tc: TraceContext): Future[Unit] =
    peers.headOption match {
      case None =>
        Future.failed(new CopyFailed(obj.key, failures.reverse))
      case Some(peer) =>
        copyFromPeer(obj, peer).recoverWith {
          case e: ChecksumMismatch =>
            logger.warn(e.getMessage)
            copyFromAnyPeer(obj, peers.drop(1), s"$peer: ${e.getMessage}" :: failures)
          case e =>
            logger.info(
              s"Failed to obtain object ${obj.key} from peer $peer, trying the next: ${e.getMessage}"
            )
            copyFromAnyPeer(obj, peers.drop(1), s"$peer: ${e.getMessage}" :: failures)
        }
    }

  private def copyFromPeer(obj: ObjectKeyAndChecksum, peer: String)(implicit
      tc: TraceContext
  ): Future[Unit] =
    source.open(peer, obj.key).flatMap { download =>
      val writer = staging.newAppendWriteObject(obj.key)
      val digest = MessageDigest.getInstance("SHA-256")
      download
        .groupedWeighted(uploadPartSize.toLong)(_.size.toLong)
        .map(chunks => chunks.foldLeft(ByteString.empty)(_ ++ _).asByteBuffer)
        .mapAsync(1) { part =>
          digest.update(part.duplicate())
          writer.upload(writer.prepareUploadNext(part), part)
        }
        .runWith(Sink.ignore)
        .flatMap { _ =>
          val actual = Base64.getEncoder.encodeToString(digest.digest())
          if (actual == obj.checksum) writer.finish()
          else
            Future.failed(
              new ChecksumMismatch(obj.key, peer, expected = obj.checksum, actual = actual)
            )
        }
        .recoverWith { case e =>
          writer.abort().transformWith(_ => Future.failed(e))
        }
    }
}

object VerifiedObjectCopier {
  val uploadPartSize: Int = 8 * 1024 * 1024

  val randomPeerOrder: Seq[String] => Seq[String] = peers => Random.shuffle(peers)

  final class ChecksumMismatch(key: String, peer: String, expected: String, actual: String)
      extends RuntimeException(
        s"Checksum mismatch for object $key from peer $peer: expected $expected, got $actual"
      )

  final class CopyFailed(key: String, failures: Seq[String])
      extends RuntimeException(
        s"Could not copy object $key from any peer: ${failures.mkString("; ")}"
      )
}

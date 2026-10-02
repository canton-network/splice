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
          _ <- downloadVerifiedFromAny(obj, peerOrder(peers), Nil)
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

  private def downloadVerifiedFromAny(
      obj: ObjectKeyAndChecksum,
      peers: Seq[String],
      failures: List[String],
  )(implicit tc: TraceContext): Future[Unit] =
    peers.headOption match {
      case None =>
        Future.failed(new CopyFailed(obj.key, failures.reverse))
      case Some(peer) =>
        downloadVerified(obj, peer).transformWith {
          case scala.util.Success(bytes) => upload(obj.key, bytes)
          case scala.util.Failure(e: ChecksumMismatch) =>
            logger.warn(e.getMessage)
            downloadVerifiedFromAny(obj, peers.drop(1), s"$peer: ${e.getMessage}" :: failures)
          case scala.util.Failure(e) =>
            logger.info(
              s"Failed to obtain object ${obj.key} from peer $peer, trying the next: ${e.getMessage}"
            )
            downloadVerifiedFromAny(obj, peers.drop(1), s"$peer: ${e.getMessage}" :: failures)
        }
    }

  private def downloadVerified(obj: ObjectKeyAndChecksum, peer: String)(implicit
      tc: TraceContext
  ): Future[ByteString] =
    for {
      src <- source.open(peer, obj.key)
      bytes <- src.runWith(Sink.fold(ByteString.empty)(_ ++ _))
      _ <- {
        val digest = sha256Base64(bytes)
        if (digest == obj.checksum) Future.unit
        else
          Future.failed(
            new ChecksumMismatch(obj.key, peer, expected = obj.checksum, actual = digest)
          )
      }
    } yield bytes

  private def upload(key: String, bytes: ByteString): Future[Unit] = {
    val writer = staging.newAppendWriteObject(key)
    val parts = bytes.grouped(uploadPartSize).map(_.asByteBuffer).toVector
    val numbered = parts.map(part => writer.prepareUploadNext(part) -> part)
    numbered
      .foldLeft(Future.unit) { case (acc, (partNumber, part)) =>
        acc.flatMap(_ => writer.upload(partNumber, part))
      }
      .flatMap(_ => writer.finish())
  }
}

object VerifiedObjectCopier {
  val uploadPartSize: Int = 8 * 1024 * 1024

  val randomPeerOrder: Seq[String] => Seq[String] = peers => Random.shuffle(peers)

  def sha256Base64(bytes: ByteString): String =
    Base64.getEncoder.encodeToString(
      MessageDigest.getInstance("SHA-256").digest(bytes.toArrayUnsafe())
    )

  final class ChecksumMismatch(key: String, peer: String, expected: String, actual: String)
      extends RuntimeException(
        s"Checksum mismatch for object $key from peer $peer: expected $expected, got $actual"
      )

  final class CopyFailed(key: String, failures: Seq[String])
      extends RuntimeException(
        s"Could not copy object $key from any peer: ${failures.mkString("; ")}"
      )
}

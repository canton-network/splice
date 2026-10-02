// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.scan.store.bulk.S3BucketConnectionForUnitTests
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.{HasS3Mock, S3BucketConnectionForTests, StoreTestBase}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.util.Random

class BulkStorageVerifiedObjectCopierTest
    extends StoreTestBase
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock {

  override val initialBuckets: Seq[String] = Seq("peer1", "peer2", "staging", "committed")

  private def peerBucket(name: String) =
    new S3BucketConnectionForUnitTests(s3ConfigMock(name), loggerFactory)

  private def localBucket(name: String) =
    new S3BucketConnectionForTests(s3ConfigMock(name), loggerFactory)

  private def putOnPeers(key: String, content: ByteString): Future[String] = {
    def put(bucketName: String) = {
      val writer = peerBucket(bucketName).newAppendWriteObject(key)
      val part = content.asByteBuffer
      writer.prepareUploadNext(part)
      writer.upload(1, part).flatMap(_ => writer.finish())
    }
    for {
      _ <- put("peer1")
      _ <- put("peer2")
      checksums <- peerBucket("peer1").getChecksums(Seq(key))
    } yield checksums.head.checksum
  }

  private def storedBytes(bucketName: String, key: String): Future[ByteString] =
    localBucket(bucketName).readObject(key).flatMap(_.runWith(Sink.fold(ByteString.empty)(_ ++ _)))

  private class BucketPeers(corrupt: Set[String]) extends PeerObjectSource {
    val opens = new AtomicInteger(0)
    override def peers(implicit tc: TraceContext): Future[Seq[String]] =
      Future.successful(Seq("peer1", "peer2"))
    override def open(peer: String, key: String)(implicit
        tc: TraceContext
    ): Future[Source[ByteString, Any]] = {
      opens.incrementAndGet()
      localBucket(peer).readObject(key).map[Source[ByteString, Any]] { src =>
        if (corrupt.contains(peer)) src.map(bs => bs ++ ByteString("x")) else src
      }
    }
  }

  private def copier(source: PeerObjectSource) =
    new VerifiedObjectCopier(
      source,
      identity,
      localBucket("staging"),
      localBucket("committed"),
      parallelism = 1,
      loggerFactory,
    )

  private val content = ByteString(Random.nextBytes(1000))
  private val objectKey = "2026-01-01T00:00:00Z~2026-01-02T00:00:00Z/updates_compact_json_0.zstd"

  "VerifiedObjectCopier" should {
    "copy an object whose bytes match the agreed checksum into staging, byte for byte" in {
      for {
        digest <- putOnPeers(objectKey, content)
        _ <- copier(new BucketPeers(Set.empty)).copy(Seq(ObjectKeyAndChecksum(objectKey, digest)))
        onPeer <- storedBytes("peer1", objectKey)
        staged <- storedBytes("staging", objectKey)
        checksums <- localBucket("staging").getChecksums(Seq(objectKey))
      } yield {
        staged shouldBe onPeer
        checksums.map(_.checksum) shouldBe Seq(digest)
      }
    }

    "fall back to the next peer when the first one returns different bytes" in {
      val peers = new BucketPeers(corrupt = Set("peer1"))
      for {
        digest <- putOnPeers(objectKey, content)
        _ <- loggerFactory.assertLogs(
          copier(peers).copy(Seq(ObjectKeyAndChecksum(objectKey, digest))),
          _.warningMessage should include("Checksum mismatch for object"),
        )
        checksums <- localBucket("staging").getChecksums(Seq(objectKey))
      } yield {
        checksums.map(_.checksum) shouldBe Seq(digest)
        peers.opens.get() shouldBe 2
      }
    }

    "fail when no peer serves the agreed bytes" in {
      for {
        digest <- putOnPeers(objectKey, content)
        result <- loggerFactory.assertLogs(
          copier(new BucketPeers(corrupt = Set("peer1", "peer2")))
            .copy(Seq(ObjectKeyAndChecksum(objectKey, digest)))
            .transform(t => scala.util.Success(t)),
          _.warningMessage should include("from peer peer1"),
          _.warningMessage should include("from peer peer2"),
        )
        exists <- localBucket("staging").doesObjectExist(objectKey)
      } yield {
        result.failed.get shouldBe a[VerifiedObjectCopier.CopyFailed]
        exists shouldBe false
      }
    }

    "skip an object that staging already holds with the same checksum" in {
      val peers = new BucketPeers(Set.empty)
      for {
        digest <- putOnPeers(objectKey, content)
        _ <- copier(peers).copy(Seq(ObjectKeyAndChecksum(objectKey, digest)))
        opensAfterFirst = peers.opens.get()
        _ <- copier(peers).copy(Seq(ObjectKeyAndChecksum(objectKey, digest)))
      } yield {
        opensAfterFirst shouldBe 1
        peers.opens.get() shouldBe 1
      }
    }

    "skip an object that committed already holds with the same checksum" in {
      val peers = new BucketPeers(Set.empty)
      val writer = localBucket("committed").newAppendWriteObject(objectKey)
      val part = content.asByteBuffer
      writer.prepareUploadNext(part)
      for {
        _ <- writer.upload(1, part)
        _ <- writer.finish()
        committed <- localBucket("committed").getChecksums(Seq(objectKey))
        _ <- copier(peers).copy(Seq(ObjectKeyAndChecksum(objectKey, committed.head.checksum)))
        exists <- localBucket("staging").doesObjectExist(objectKey)
      } yield {
        peers.opens.get() shouldBe 0
        exists shouldBe false
      }
    }

    "upload objects larger than one part" in {
      val big = ByteString(Random.nextBytes(VerifiedObjectCopier.uploadPartSize + 12345))
      val bigKey = "2026-01-02T00:00:00Z~2026-01-03T00:00:00Z/updates_compact_json_0.zstd"
      for {
        digest <- putOnPeers(bigKey, big)
        _ <- copier(new BucketPeers(Set.empty)).copy(Seq(ObjectKeyAndChecksum(bigKey, digest)))
        onPeer <- storedBytes("peer1", bigKey)
        staged <- storedBytes("staging", bigKey)
        checksums <- localBucket("staging").getChecksums(Seq(bigKey))
      } yield {
        staged.size shouldBe onPeer.size
        staged shouldBe onPeer
        checksums.map(_.checksum) shouldBe Seq(digest)
      }
    }

    "order peers randomly without dropping or repeating any" in {
      val peers = (1 to 20).map(i => s"peer$i")
      val orders = (1 to 20).map(_ => VerifiedObjectCopier.randomPeerOrder(peers))
      forAll(orders)(_ should contain theSameElementsAs peers)
      orders.distinct.size should be > 1
    }
  }
}

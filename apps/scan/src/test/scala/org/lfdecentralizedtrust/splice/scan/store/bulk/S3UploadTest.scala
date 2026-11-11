// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.config.S3Config
import org.lfdecentralizedtrust.splice.store.{HasS3Mock, S3BucketConnection, StoreTestBase}
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.util.Random
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class S3UploadTest extends StoreTestBase with HasS3Mock {

  private val emptyDigest = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="

  "S3 multipart uploads" should {
    "work" in {

      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock(), loggerFactory)
      val o = bucketConnection.newAppendWriteObject("test")
      val part1 = ByteBuffer.wrap("hello".getBytes("UTF-8"))
      val part2 = ByteBuffer.wrap("world".getBytes("UTF-8"))

      o.prepareUploadNext(part1)
      o.prepareUploadNext(part2)
      for {
        _ <- o.upload(1, part1)
        _ <- o.upload(2, part2)
        _ <- o.finish()
        content <- bucketConnection.readFullObject("test")
      } yield {
        new String(content.toArray, "UTF-8") shouldBe "helloworld"
      }
    }

    "not corrupt the checksum if finish() is called more than once" in {
      val expectedContent = "idempotency test"
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock(), loggerFactory)
      val o = bucketConnection.newAppendWriteObject("finish-twice")
      val part = ByteBuffer.wrap(expectedContent.getBytes("UTF-8"))

      o.prepareUploadNext(part)
      for {
        _ <- o.upload(1, part)
        _ <- o.finish()
        checksumAfterFirstFinish <- bucketConnection.getChecksums(Seq("finish-twice"))
        _ <- o.finish()
        checksumAfterSecondFinish <- bucketConnection.getChecksums(Seq("finish-twice"))
        content <- bucketConnection.readFullObject("finish-twice")
      } yield {
        checksumAfterFirstFinish.map(_.checksum) should not contain emptyDigest
        checksumAfterSecondFinish shouldBe checksumAfterFirstFinish
        new String(content.toArray, "UTF-8") shouldBe expectedContent
      }
    }
  }

  "GroupedWeightS3Object" should {

    "just work" in {

      val data = ByteString(Random.nextBytes(100))
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock(), loggerFactory)

      val (pub, sub) = TestSource
        .probe[ByteString]
        .via(
          GroupedWeightS3ObjectFlow(
            bucketConnection,
            getObjectKey = i => s"test_$i",
            maxObjectSize = 10L,
            maxParallelPartUploads = 2,
            loggerFactory,
          )
        )
        .toMat(TestSink.probe[String])(Keep.both)
        .run()

      val it = data.iterator

      def sendBytes(n: Int) =
        pub.sendNext(it.getByteString(n))

      val inputSizes =
        Seq.fill(9)(3) :+ // 9 inputs of size 3, to test the basic functionality
          7 :+ // add 7 to exactly hit the edge of the object size (10)
          25 :+ // add an input that does not fit
          1 // finish with a tiny input
      inputSizes.foreach(sendBytes)
      pub.sendComplete()

      sub.request(5)
      val expectedObjectSizes = Seq(12, 12, 10, 25, 1)
      expectedObjectSizes.indices.foreach(i => sub.expectNext(20.seconds) shouldBe s"test_$i")
      sub.expectComplete()

      val s3Objects = bucketConnection.listObjects.futureValue
      val s3ObjKeys = s3Objects.contents.asScala.sortBy(_.key())
      val s3ObjData = s3ObjKeys.map { obj =>
        bucketConnection.readFullObject(obj.key()).futureValue
      }.toSeq
      s3ObjData.map(_.length) shouldBe expectedObjectSizes
      val dataFromS3 = s3ObjData.foldLeft(ByteString.empty) { (acc, buf) => acc ++ ByteString(buf) }
      dataFromS3 shouldBe data.take(expectedObjectSizes.sum)
    }

    "handle errors correctly" in {

      val data = ByteString(Random.nextBytes(100))
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock(), loggerFactory)

      val (pub, sub) = TestSource
        .probe[ByteString]
        .via(
          GroupedWeightS3ObjectFlow(
            bucketConnection,
            getObjectKey = i => s"test_$i",
            maxObjectSize = 10L,
            maxParallelPartUploads = 2,
            loggerFactory,
          )
        )
        .toMat(TestSink.probe[String])(Keep.both)
        .run()

      val it = data.iterator

      def sendBytes(n: Int) =
        pub.sendNext(it.getByteString(n))

      val inputSizes = Seq(6, 6, 3)
      inputSizes.foreach(sendBytes)
      pub.sendError(new RuntimeException("Injected error"))
      sub.request(1)
      sub.expectError()
      succeed
    }

    "not finish an object twice when upstream completes while the object is being finished" in {
      // Regression test for the race that produced correct object content with a wrong checksum:
      // an object that is done by size starts being finished from uploadCallback; `state` is only
      // advanced later, in the async finishCallback. Upstream completion is delivered eagerly
      // (independently of demand), so onUpstreamFinish could land in that window and call finish()
      // a second time on the very same object.
      val bucketConnection = new GatedFinishS3Connection(s3ConfigMock(), loggerFactory)

      val (pub, sub) = TestSource
        .probe[ByteString]
        .via(
          GroupedWeightS3ObjectFlow(
            bucketConnection,
            getObjectKey = i => s"race_$i",
            maxObjectSize = 10L,
            maxParallelPartUploads = 2,
            loggerFactory,
          )
        )
        .toMat(TestSink.probe[String])(Keep.both)
        .run()

      sub.request(5)
      // Exactly hits maxObjectSize, so the object is done by size and finish() is started
      // from uploadCallback as soon as the single part upload completes.
      pub.sendNext(ByteString(Random.nextBytes(10)))

      // Wait until the flow is blocked inside finish()
      eventually() {
        bucketConnection.finishCount.get() shouldBe 1
      }

      // Complete upstream while finish() is still in flight
      pub.sendComplete()
      always(durationOfSuccess = 2.seconds) {
        bucketConnection.finishCount.get() shouldBe 1
      }

      bucketConnection.releaseFinish()
      sub.expectNext(20.seconds) shouldBe "race_0"
      sub.expectComplete()

      val checksums = bucketConnection.getChecksums(Seq("race_0")).futureValue
      checksums should have size 1
      checksums.map(_.checksum) should not contain emptyDigest
      succeed
    }
  }

  /** An S3 connection whose `finish()` blocks until [[releaseFinish]] is called, and which counts
    * how many times `finish()` was invoked.
    */
  private class GatedFinishS3Connection(
      s3Config: S3Config,
      loggerFactory: NamedLoggerFactory,
  ) extends S3BucketConnectionForUnitTests(s3Config, loggerFactory) {
    val finishCount = new AtomicInteger(0)
    private val gate = Promise[Unit]()

    def releaseFinish(): Unit = { val _ = gate.trySuccess(()) }

    override def newAppendWriteObject(
        key: String
    )(implicit ec: ExecutionContext): AppendWriteObject =
      new AppendWriteObjectForUnitTests(key) {
        override def finish(): Future[Unit] = {
          val _ = finishCount.incrementAndGet()
          gate.future.flatMap(_ => super.finish())
        }
      }
  }

  "S3BucketConnection" should {
    "Get checksums does not panic on non existing object" in {
      val bucketConnection = new S3BucketConnection(s3ConfigMock(), loggerFactory)
      val checksum = bucketConnection.getChecksums(Seq("non-existing-object")).futureValue
      checksum shouldBe empty
    }
  }
}

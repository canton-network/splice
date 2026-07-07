// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.slf4j.event.Level
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.lfdecentralizedtrust.splice.scan.config.BulkStorageConfig
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.{HasS3Mock, StoreTestBase}
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest

import java.security.MessageDigest
import java.util.Base64
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

class BulkStorageCommitFromStagingTest
    extends StoreTestBase
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock
    with SplicePostgresTest {

  override val initialBuckets = Seq("staging", "committed")
  val appConfig = BulkStorageConfig()

  "BulkStorageCommitFromStaging" should {
    "successfully move objects from staging to committed S3 bucket" in {

      val (stagingS3Connection, committedS3Connection, objsWithDigests) = setupTest

      triggerCopyFlow(stagingS3Connection, committedS3Connection, objsWithDigests)

      assertObjectsMoved(stagingS3Connection, committedS3Connection, objsWithDigests)
    }

    "skip previously copied objects" in {
      val (stagingS3Connection, committedS3Connection, objsWithDigests) = setupTest

      /* Pre-copy one object to the committed bucket to simulate the pipeline/app restarting during the copy process.
       * The copy flow should skip this object and not attempt to copy it again.
       */
      val preCopiedObject = objsWithDigests.head
      committedS3Connection
        .copyObject(stagingS3Connection.bucketName, preCopiedObject.key)
        .futureValue

      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          triggerCopyFlow(stagingS3Connection, committedS3Connection, objsWithDigests)
        },
        logEntries => forExactly(1, logEntries)(_.message should include("Skipping copy")),
      )

      assertObjectsMoved(stagingS3Connection, committedS3Connection, objsWithDigests)
    }
  }

  private def triggerCopyFlow(
      stagingS3Connection: S3BucketConnectionForUnitTests,
      committedS3Connection: S3BucketConnectionForUnitTests,
      objsWithDigests: Seq[ObjectKeyAndChecksum],
  ) = {
    val flow = BulkStorageCommitFromStaging[String](
      stagingS3Connection,
      committedS3Connection,
      _ => Future.successful(objsWithDigests),
      appConfig,
      loggerFactory,
    )

    val (pub, sub) = TestSource
      .probe[String]
      .via(flow)
      .toMat(TestSink.probe[String])(Keep.both)
      .run()

    sub.request(1)
    pub.sendNext("go")
    sub.expectNext("go")
  }

  private def assertObjectsMoved(
      stagingS3Connection: S3BucketConnectionForUnitTests,
      committedS3Connection: S3BucketConnectionForUnitTests,
      objsWithDigests: Seq[ObjectKeyAndChecksum],
  ) = {
    clue("All objects have been moved from staging to committed S3 bucket") {
      stagingS3Connection.listObjects.futureValue.contents().asScala shouldBe empty
      committedS3Connection.listObjects.futureValue
        .contents()
        .asScala
        .map(_.key()) should contain theSameElementsAs objsWithDigests.map(_.key)
    }
    clue("Checksums of objects in committed S3 bucket match the expected digests") {
      committedS3Connection
        .getChecksums(objsWithDigests.map(_.key))
        .futureValue should contain theSameElementsAs objsWithDigests
    }
  }

  private def setupTest = {
    val stagingS3Connection =
      new S3BucketConnectionForUnitTests(s3ConfigMock("staging"), loggerFactory)
    val committedS3Connection =
      new S3BucketConnectionForUnitTests(s3ConfigMock("committed"), loggerFactory)

    def createStagingObject(content: String) = {
      val md = MessageDigest.getInstance("SHA-256")
      md.update(content.getBytes)
      val digest = Base64.getEncoder.encodeToString(md.digest())
      val key = s"$content.txt"
      stagingS3Connection.createObject(key, content.getBytes).futureValue
      ObjectKeyAndChecksum(key, digest)
    }

    val objsWithDigests =
      Seq("object1", "object2", "object3").map(createStagingObject)
    (stagingS3Connection, committedS3Connection, objsWithDigests)
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}

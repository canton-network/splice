package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.{HasS3Mock, S3BucketConnection, StoreTestBase}
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest

import java.security.MessageDigest
import java.util.Base64
import scala.concurrent.Future

class BulkStorageCommitFromStagingTest
    extends StoreTestBase
    with BulkStorageCommitFromStaging[String]
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock
    with SplicePostgresTest {

  override val initialBuckets = Seq("staging", "committed")

  "BulkStorageCommitFromStaging" should {
    "successfully copy objects from staging to committed S3 bucket" in {

      val stagingS3Connection = new S3BucketConnectionForUnitTests(s3ConfigMock("staging"), loggerFactory)
      val committedS3Connection = new S3BucketConnectionForUnitTests(s3ConfigMock("committed"), loggerFactory)

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

      val flow = processFlow(stagingS3Connection, committedS3Connection, _ => Future.successful(objsWithDigests))


//      val stagingObjects = Seq(
//        ObjectKeyAndChecksum("staging/object1.txt", "checksum1"),
//        ObjectKeyAndChecksum("staging/object2.txt", "checksum2")
//      )
//
//      val committedObjects = Seq(
//        ObjectKeyAndChecksum("committed/object1.txt", "checksum1"),
//        ObjectKeyAndChecksum("committed/object2.txt", "checksum2")
//      )
//
//      // Simulate the process of copying objects from staging to committed
//      val copyResult = copyObjectsToCommitted(stagingObjects)
//
//      // Verify that the objects have been copied successfully
//      copyResult.map { result =>
//        result shouldBe committedObjects
//      }
    }
  }

  override protected def cleanDb(storage: DbStorage)(implicit
      tc: TraceContext
  ): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}

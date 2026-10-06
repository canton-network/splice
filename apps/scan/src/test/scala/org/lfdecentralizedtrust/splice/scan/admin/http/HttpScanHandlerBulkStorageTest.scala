// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import cats.data.NonEmptyList
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.config.SpliceInstanceNamesConfig
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  PackageVersionSupport,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.scan.ScanResource
import org.lfdecentralizedtrust.splice.scan.ScanSynchronizerNode
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.scan.dso.DsoAnsResolver
import org.lfdecentralizedtrust.splice.scan.metrics.ScanHttpApiMetrics
import org.lfdecentralizedtrust.splice.scan.store.{
  AcsSnapshotStore,
  AppActivityStore,
  ScanEventStore,
  ScanStore,
}
import org.lfdecentralizedtrust.splice.scan.store.bulk.{
  AcsSnapshotBulkStoragePersistentProgress,
  BulkStorageReader,
  UpdateHistoryBulkStoragePersistentProgress,
  UpdatesSegment,
}
import org.lfdecentralizedtrust.splice.scan.store.bulk.AcsSnapshotBulkStorage.AcsSnapshotObjects
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdateHistoryBulkStorage.UpdateHistoryObjectsResponse
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore
import org.lfdecentralizedtrust.splice.store.{
  AppStoreWithIngestion,
  PageLimit,
  S3BucketConnection,
  TimestampWithMigrationId,
  UpdateHistory,
}
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, ZoneOffset}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class HttpScanHandlerBulkStorageTest extends AnyWordSpec with BaseTest {

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val actorSystem: ActorSystem = ActorSystem("HttpScanHandlerBulkStorageTest")
  implicit val tc: TraceContext = TraceContext.empty

  private val instanceNames = SpliceInstanceNamesConfig(
    networkName = "network",
    networkFaviconUrl = "https://example.invalid/favicon",
    amuletName = "amulet",
    amuletNameAcronym = "A",
    nameServiceName = "name-service",
    nameServiceNameAcronym = "NS",
  )

  private def handler(
      bulkStorage: Option[BulkStorageReader],
      publicUrlO: Option[Uri] = None,
  ): HttpScanHandler = {
    val scanStore = mock[ScanStore]
    val storeWithIngestion = mock[AppStoreWithIngestion[ScanStore]]
    when(storeWithIngestion.store).thenReturn(scanStore)

    new HttpScanHandler(
      svParty = PartyId.tryFromProtoPrimitive("sv::dummy"),
      svUserName = "sv-user",
      spliceInstanceNames = instanceNames,
      participantAdminConnection = mock[ParticipantAdminConnection],
      synchronizerNodeService = mock[SynchronizerNodeService[ScanSynchronizerNode]],
      storeWithIngestion = storeWithIngestion,
      updateHistory = mock[UpdateHistory],
      appRewardsStore = mock[DbScanAppRewardsStore],
      appActivityStore = mock[AppActivityStore],
      snapshotStore = mock[AcsSnapshotStore],
      eventStore = mock[ScanEventStore],
      bulkStorage = bulkStorage,
      scanApiMetrics = mock[ScanHttpApiMetrics],
      dsoAnsResolver = mock[DsoAnsResolver],
      miningRoundsCacheTimeToLiveOverride = None,
      enableForcedAcsSnapshots = false,
      clock = mock[Clock],
      loggerFactory = NamedLoggerFactory.root,
      packageVersionSupport = mock[PackageVersionSupport],
      bftSequencers = Seq.empty,
      initialRound = "0",
      externalTransactionHashThresholdTime = None,
      updateHistoryMaxPageSize = 100,
      publicUrlO = publicUrlO,
      lsuRollForwardConfigO = None,
      perAcsSnapshotTablesEnabled = true,
    )
  }

  private def assertGrpcError[A](
      f: => Future[A],
      expectedCode: Status.Code,
      expectedDescription: String,
  ): Unit = {
    val ex = f.failed.futureValue
    ex shouldBe a[StatusRuntimeException]
    val grpcEx = ex.asInstanceOf[StatusRuntimeException]
    grpcEx.getStatus.getCode shouldBe expectedCode
    grpcEx.getStatus.getDescription should include(expectedDescription)
  }

  private def snapshotProgressAt(instant: String): TimestampWithMigrationId =
    TimestampWithMigrationId(
      com.digitalasset.canton.data.CantonTimestamp.assertFromInstant(Instant.parse(instant)),
      0L,
    )

  private def updateProgress(fromInstant: String, toInstant: String): UpdatesSegment =
    UpdatesSegment(
      fromTimestamp = snapshotProgressAt(fromInstant),
      toTimestamp = snapshotProgressAt(toInstant),
    )

  private def bulkStorageReader(
      snapshotProgressO: Option[TimestampWithMigrationId],
      updateProgressO: Option[UpdatesSegment],
  ): BulkStorageReader = {
    val acsSnapshotStagingProgress = mock[AcsSnapshotBulkStoragePersistentProgress]
    val updateHistoryStagingProgress = mock[UpdateHistoryBulkStoragePersistentProgress]
    when(
      acsSnapshotStagingProgress.readLatestProcessedSnapshotTimestamp(
        any[TraceContext],
        any[ExecutionContext],
      )
    ).thenReturn(Future.successful(snapshotProgressO))
    when(
      updateHistoryStagingProgress.readLatestProcessedSegment(
        any[TraceContext],
        any[ExecutionContext],
      )
    ).thenReturn(Future.successful(updateProgressO))

    new BulkStorageReader(
      acsSnapshotStagingProgress = acsSnapshotStagingProgress,
      acsSnapshotCommittedProgress = mock[AcsSnapshotBulkStoragePersistentProgress],
      updateHistoryStagingProgress = updateHistoryStagingProgress,
      updateHistoryCommittedProgress = mock[UpdateHistoryBulkStoragePersistentProgress],
      storageConfig = mock[ScanStorageConfig],
      stagingS3Connection = mock[S3BucketConnection],
      committedS3Connection = mock[S3BucketConnection],
      loggerFactory = NamedLoggerFactory.root,
    )
  }

  "HttpScanHandler bulk-storage endpoints" should {
    "return UNIMPLEMENTED when bulk storage is not configured for checksum lookups" in {
      val h = handler(bulkStorage = None)
      val request = definitions.GetBulkObjectChecksumsRequest(
        objectKeys = Vector("object-1")
      )
      assertGrpcError(
        h.getBulkObjectChecksums(ScanResource.GetBulkObjectChecksumsResponse)(request)(
          TraceContext.empty
        ),
        Status.Code.UNIMPLEMENTED,
        "Bulk storage is not configured",
      )
    }

    "GetBulkObjectsProgress returns true when enough progress was made" in {
      val snapshotProgress = snapshotProgressAt("2023-12-31T00:00:00Z")
      val updateRange = updateProgress("2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z")
      val bulkStorage = bulkStorageReader(Some(snapshotProgress), Some(updateRange))
      val h = handler(bulkStorage = Some(bulkStorage))
      val requiredCatchupTimestamp = Instant.parse("2023-12-31T00:00:00Z").atOffset(ZoneOffset.UTC)
      val response = h
        .getBulkObjectsProgress(ScanResource.GetBulkObjectsProgressResponse)(
          requiredCatchupTimestamp
        )(TraceContext.empty)
        .futureValue
      inside(response) { case ScanResource.GetBulkObjectsProgressResponseOK(value) =>
        value.beyondRequestedRecordTime shouldBe true
      }
    }

    "GetBulkObjectsProgress returns false when snapshot progress is behind the required catch-up timestamp" in {
      val snapshotProgress = snapshotProgressAt("2023-12-31T00:00:00Z")
      val updateRange = updateProgress("2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z")
      val bulkStorage = bulkStorageReader(Some(snapshotProgress), Some(updateRange))
      val h = handler(bulkStorage = Some(bulkStorage))
      val requiredCatchupTimestamp = Instant.parse("2024-01-01T00:00:00Z").atOffset(ZoneOffset.UTC)
      val response = h
        .getBulkObjectsProgress(ScanResource.GetBulkObjectsProgressResponse)(
          requiredCatchupTimestamp
        )(TraceContext.empty)
        .futureValue
      inside(response) { case ScanResource.GetBulkObjectsProgressResponseOK(value) =>
        value.beyondRequestedRecordTime shouldBe false
      }
    }

    "GetBulkObjectsProgress returns false when updates progress is behind the required catch-up timestamp" in {
      val snapshotProgress = snapshotProgressAt("2024-01-02T00:00:00Z")
      val updateRange = updateProgress("2023-12-30T00:00:00Z", "2023-12-31T00:00:00Z")
      val bulkStorage = bulkStorageReader(Some(snapshotProgress), Some(updateRange))
      val h = handler(bulkStorage = Some(bulkStorage))
      val requiredCatchupTimestamp = Instant.parse("2024-01-01T00:00:00Z").atOffset(ZoneOffset.UTC)
      val response = h
        .getBulkObjectsProgress(ScanResource.GetBulkObjectsProgressResponse)(
          requiredCatchupTimestamp
        )(TraceContext.empty)
        .futureValue
      inside(response) { case ScanResource.GetBulkObjectsProgressResponseOK(value) =>
        value.beyondRequestedRecordTime shouldBe false
      }
    }

    "list update history objects in compact_json unless other encodings are requested" in {
      val reader = mock[BulkStorageReader]
      when(
        reader.getCommittedUpdatesBetweenDates(
          any[CantonTimestamp],
          any[CantonTimestamp],
          any[PageLimit],
          any[Option[String]],
          any[NonEmptyList[ScanStorageConfig.Encoding]],
        )(any[TraceContext], any[ExecutionContext])
      ).thenReturn(Future.successful(UpdateHistoryObjectsResponse(Seq.empty, None)))
      val h = handler(bulkStorage = Some(reader), publicUrlO = Some(Uri("http://scan.example.com")))
      def list(encodings: Option[Vector[definitions.DamlValueEncoding]]) =
        h.listBulkUpdateHistoryObjects(ScanResource.ListBulkUpdateHistoryObjectsResponse)(
          definitions.ListBulkUpdateHistoryObjectsRequest(
            startRecordTime = Instant.parse("2024-01-01T00:00:00Z").atOffset(ZoneOffset.UTC),
            endRecordTime = Instant.parse("2024-01-02T00:00:00Z").atOffset(ZoneOffset.UTC),
            pageSize = 10,
            damlValueEncodings = encodings,
          )
        )(TraceContext.empty)
          .futureValue

      list(None)
      list(
        Some(
          Vector(
            definitions.DamlValueEncoding.ProtobufJson,
            definitions.DamlValueEncoding.CompactJson,
          )
        )
      )

      verify(reader).getCommittedUpdatesBetweenDates(
        any[CantonTimestamp],
        any[CantonTimestamp],
        any[PageLimit],
        any[Option[String]],
        eqTo(NonEmptyList.one(ScanStorageConfig.Encoding.CompactJson)),
      )(any[TraceContext], any[ExecutionContext])
      verify(reader).getCommittedUpdatesBetweenDates(
        any[CantonTimestamp],
        any[CantonTimestamp],
        any[PageLimit],
        any[Option[String]],
        eqTo(
          NonEmptyList.of[ScanStorageConfig.Encoding](
            ScanStorageConfig.Encoding.ProtobufJson,
            ScanStorageConfig.Encoding.CompactJson,
          )
        ),
      )(any[TraceContext], any[ExecutionContext])
    }

    "list ACS snapshot objects in the requested encodings" in {
      val reader = mock[BulkStorageReader]
      val snapshotTime = CantonTimestamp.assertFromInstant(Instant.parse("2024-01-01T00:00:00Z"))
      when(
        reader.getCommittedObjectsForAcsSnapshotAtOrBefore(
          any[CantonTimestamp],
          any[NonEmptyList[ScanStorageConfig.Encoding]],
        )(any[TraceContext], any[ExecutionContext])
      ).thenReturn(Future.successful(AcsSnapshotObjects(snapshotTime, Seq.empty)))
      val h = handler(bulkStorage = Some(reader), publicUrlO = Some(Uri("http://scan.example.com")))

      h.listBulkAcsSnapshotObjects(ScanResource.ListBulkAcsSnapshotObjectsResponse)(
        snapshotTime.toInstant.atOffset(ZoneOffset.UTC),
        Some(Vector(definitions.DamlValueEncoding.ProtobufJson)),
      )(TraceContext.empty)
        .futureValue

      verify(reader).getCommittedObjectsForAcsSnapshotAtOrBefore(
        any[CantonTimestamp],
        eqTo(NonEmptyList.one(ScanStorageConfig.Encoding.ProtobufJson)),
      )(any[TraceContext], any[ExecutionContext])
    }

    "GetBulkObjectsProgress returns false when progress is not initialized" in {
      val bulkStorage = bulkStorageReader(None, None)
      val h = handler(bulkStorage = Some(bulkStorage))
      val requiredCatchupTimestamp = Instant.parse("2024-01-01T00:00:00Z").atOffset(ZoneOffset.UTC)
      val response = h
        .getBulkObjectsProgress(ScanResource.GetBulkObjectsProgressResponse)(
          requiredCatchupTimestamp
        )(TraceContext.empty)
        .futureValue
      inside(response) { case ScanResource.GetBulkObjectsProgressResponseOK(value) =>
        value.beyondRequestedRecordTime shouldBe false
      }
    }
  }
}

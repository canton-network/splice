// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ExtActionRequiringConformation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.voterequestoutcome.VRO_Expired
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequestResult,
  Reason,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.config.SpliceInstanceNamesConfig
import org.lfdecentralizedtrust.splice.environment.{
  PackageVersionSupport,
  ParticipantAdminConnection,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.scan.ScanResource
import org.lfdecentralizedtrust.splice.scan.ScanSynchronizerNode
import org.lfdecentralizedtrust.splice.scan.dso.DsoAnsResolver
import org.lfdecentralizedtrust.splice.scan.metrics.ScanHttpApiMetrics
import org.lfdecentralizedtrust.splice.scan.store.{
  AcsSnapshotStore,
  AppActivityStore,
  ScanEventStore,
  ScanStore,
}
import org.lfdecentralizedtrust.splice.scan.store.db.{DbScanAppRewardsStore, DbVotesStore}
import org.lfdecentralizedtrust.splice.store.{
  AppStoreWithIngestion,
  HistoricalVotesStore,
  Limit,
  UpdateHistory,
}
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class HttpScanHandlerVoteResultsV1Test extends AnyWordSpec with BaseTest {

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val actorSystem: ActorSystem = ActorSystem("HttpScanHandlerVoteResultsV1Test")
  implicit val tc: TraceContext = TraceContext.empty

  private val recordTime = CantonTimestamp.assertFromInstant(Instant.parse("2026-01-02T03:04:05Z"))

  private val pageToken = DbVotesStore.VoteResultsPageToken(recordTime, recordTime, 3)

  private val voteResult = {
    val at = Instant.parse("2026-01-01T00:00:00Z")
    new DsoRules_CloseVoteRequestResult(
      new VoteRequest(
        "dso::1220",
        "sv1",
        new ExtActionRequiringConformation(com.daml.ledger.javaapi.data.Unit.getInstance()),
        new Reason("url", "body"),
        at,
        java.util.Map.of(),
        java.util.Optional.of(new VoteRequest.ContractId("00" + "ab" * 32)),
        java.util.Optional.empty(),
      ),
      at,
      java.util.List.of(),
      java.util.List.of(),
      new VRO_Expired(com.daml.ledger.javaapi.data.Unit.getInstance()),
    )
  }

  // With a field that the codegen of this app does not know, as if stored by a newer version of the app
  private val storedJson = {
    val json = io.circe.parser.parse(voteResult.toJson).value
    json.mapObject(_.add("fieldFromTheFuture", io.circe.Json.fromString("value")))
  }

  private def storedVoteResult(contractId: Option[String]) = DbVotesStore.ServedVoteResult(
    recordTime = recordTime,
    synchronizerId = "sync::1220",
    updateId = "update-1",
    eventNumber = 0,
    contractId = contractId,
    choiceResult = storedJson.noSpaces,
  )

  private def handler(
      backfillingComplete: Boolean,
      rows: Seq[DbVotesStore.ServedVoteResult] = Seq.empty,
  ): (HttpScanHandler, DbVotesStore) = {
    val scanStore = mock[ScanStore]
    val storeWithIngestion = mock[AppStoreWithIngestion[ScanStore]]
    when(storeWithIngestion.store).thenReturn(scanStore)

    val votesStore = mock[DbVotesStore]
    when(votesStore.isBackfillingComplete()(any[TraceContext]))
      .thenReturn(Future.successful(backfillingComplete))
    when(
      votesStore.listVoteResults(
        any[DbVotesStore.Filters],
        any[Limit],
        any[Option[DbVotesStore.VoteResultsPageToken]],
      )(any[TraceContext])
    ).thenReturn(Future.successful(DbVotesStore.VoteResultsPage(rows, Some(pageToken))))
    when(votesStore.countVoteResults(any[DbVotesStore.Filters])(any[TraceContext]))
      .thenReturn(Future.successful(rows.size.toLong))

    val handler = new HttpScanHandler(
      svParty = PartyId.tryFromProtoPrimitive("sv::dummy"),
      svUserName = "sv-user",
      spliceInstanceNames = SpliceInstanceNamesConfig(
        networkName = "network",
        networkFaviconUrl = "https://example.invalid/favicon",
        amuletName = "amulet",
        amuletNameAcronym = "A",
        nameServiceName = "name-service",
        nameServiceNameAcronym = "NS",
      ),
      participantAdminConnection = mock[ParticipantAdminConnection],
      synchronizerNodeService = mock[SynchronizerNodeService[ScanSynchronizerNode]],
      storeWithIngestion = storeWithIngestion,
      updateHistory = mock[UpdateHistory],
      appRewardsStore = mock[DbScanAppRewardsStore],
      appActivityStore = mock[AppActivityStore],
      snapshotStore = mock[AcsSnapshotStore],
      eventStore = mock[ScanEventStore],
      historicalVotesStore = mock[HistoricalVotesStore],
      votesStore = votesStore,
      bulkStorage = None,
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
      publicUrlO = None,
      lsuRollForwardConfigO = None,
      perAcsSnapshotTablesEnabled = true,
    )
    (handler, votesStore)
  }

  private def listRequest(filters: Option[definitions.VoteResultsFiltersV1] = None) =
    definitions.ListVoteResultsRequestV1(filters = filters, pageSize = 10)

  private def assertGrpcError[A](f: => Future[A], expectedCode: Status.Code): Unit = {
    val ex = f.failed.futureValue
    ex shouldBe a[StatusRuntimeException]
    ex.asInstanceOf[StatusRuntimeException].getStatus.getCode shouldBe expectedCode
  }

  "listVoteRequestResultsV1" should {

    "fail with UNAVAILABLE while the votes store is backfilling" in {
      val (h, votesStore) =
        handler(backfillingComplete = false, Seq(storedVoteResult(Some("00dso"))))
      assertGrpcError(
        h.listVoteRequestResultsV1(ScanResource.ListVoteRequestResultsV1Response)(listRequest())(
          tc
        ),
        Status.Code.UNAVAILABLE,
      )
      verify(votesStore, never)
        .listVoteResults(
          any[DbVotesStore.Filters],
          any[Limit],
          any[Option[DbVotesStore.VoteResultsPageToken]],
        )(any[TraceContext])
    }

    "serve the vote results, with the vote result as stored" in {
      val (h, _) = handler(backfillingComplete = true, Seq(storedVoteResult(Some("00dso"))))
      val response = h
        .listVoteRequestResultsV1(ScanResource.ListVoteRequestResultsV1Response)(listRequest())(
          tc
        )
        .futureValue
      inside(response) { case ScanResource.ListVoteRequestResultsV1ResponseOK(page) =>
        page.nextPageToken shouldBe Some(pageToken.encodeToBase64)
        val result = page.voteResults.loneElement
        result.recordTime shouldBe recordTime.toInstant.atOffset(ZoneOffset.UTC)
        result.synchronizerId shouldBe "sync::1220"
        result.updateId shouldBe "update-1"
        result.eventNumber shouldBe 0
        result.dsoRulesContractId shouldBe "00dso"
        result.voteResult shouldBe storedJson
        result.dsoRules shouldBe None
      }
    }

    "fail with INTERNAL if a vote result has no DsoRules contract id" in {
      val (h, _) = handler(backfillingComplete = true, Seq(storedVoteResult(None)))
      assertGrpcError(
        h.listVoteRequestResultsV1(ScanResource.ListVoteRequestResultsV1Response)(listRequest())(
          tc
        ),
        Status.Code.INTERNAL,
      )
    }

    "pass on the filters and the page token" in {
      val (h, votesStore) = handler(backfillingComplete = true)
      h.listVoteRequestResultsV1(ScanResource.ListVoteRequestResultsV1Response)(
        definitions.ListVoteResultsRequestV1(
          filters = Some(
            definitions.VoteResultsFiltersV1(
              actionName = Some("SRARC_AddSv"),
              accepted = Some(true),
              requester = Some("sv1"),
              effectiveFrom = Some(OffsetDateTime.parse("2026-01-01T02:00:00+02:00")),
              effectiveTo = Some(OffsetDateTime.parse("2026-02-01T00:00:00Z")),
            )
          ),
          pageSize = 10,
          pageToken = Some(pageToken.encodeToBase64),
        )
      )(tc)
        .futureValue
      verify(votesStore).listVoteResults(
        eqTo(
          DbVotesStore.Filters(
            actionName = Some("SRARC_AddSv"),
            accepted = Some(true),
            requester = Some("sv1"),
            effectiveFrom =
              Some(CantonTimestamp.assertFromInstant(Instant.parse("2026-01-01T00:00:00Z"))),
            effectiveTo =
              Some(CantonTimestamp.assertFromInstant(Instant.parse("2026-02-01T00:00:00Z"))),
          )
        ),
        any[Limit],
        eqTo(Some(pageToken)),
      )(any[TraceContext])
    }

    "fail with INVALID_ARGUMENT for a date that is out of range" in {
      val (h, _) = handler(backfillingComplete = true)
      assertGrpcError(
        h.listVoteRequestResultsV1(ScanResource.ListVoteRequestResultsV1Response)(
          listRequest(
            Some(definitions.VoteResultsFiltersV1(effectiveFrom = Some(OffsetDateTime.MIN)))
          )
        )(tc),
        Status.Code.INVALID_ARGUMENT,
      )
    }

    "fail with INVALID_ARGUMENT for an invalid page token" in {
      val (h, _) = handler(backfillingComplete = true)
      assertGrpcError(
        h.listVoteRequestResultsV1(ScanResource.ListVoteRequestResultsV1Response)(
          listRequest().copy(pageToken = Some("42"))
        )(tc),
        Status.Code.INVALID_ARGUMENT,
      )
    }
  }

  "countVoteRequestResultsV1" should {

    "fail with UNAVAILABLE while the votes store is backfilling" in {
      val (h, votesStore) = handler(backfillingComplete = false)
      assertGrpcError(
        h.countVoteRequestResultsV1(ScanResource.CountVoteRequestResultsV1Response)(
          definitions.VoteResultsFiltersV1()
        )(tc),
        Status.Code.UNAVAILABLE,
      )
      verify(votesStore, never).countVoteResults(any[DbVotesStore.Filters])(any[TraceContext])
    }

    "count the vote results" in {
      val (h, _) = handler(
        backfillingComplete = true,
        Seq(storedVoteResult(Some("00dso")), storedVoteResult(Some("00dso"))),
      )
      h.countVoteRequestResultsV1(ScanResource.CountVoteRequestResultsV1Response)(
        definitions.VoteResultsFiltersV1()
      )(tc)
        .futureValue shouldBe ScanResource.CountVoteRequestResultsV1Response.OK(
        definitions.CountVoteResultsResponseV1(2L)
      )
    }
  }
}

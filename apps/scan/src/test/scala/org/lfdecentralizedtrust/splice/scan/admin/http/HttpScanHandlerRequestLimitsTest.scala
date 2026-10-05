// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import cats.data.NonEmptyVector
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.VoteRequest
import org.lfdecentralizedtrust.splice.codegen.java.splice.issuance.IssuanceConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  IssuingMiningRound,
  OpenMiningRound,
  SummarizingMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.config.SpliceInstanceNamesConfig
import org.lfdecentralizedtrust.splice.environment.{
  PackageVersionSupport,
  ParticipantAdminConnection,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.http.HttpRequestLimits
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
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  HoldingsSummaryResult,
  QueryAcsSnapshotResult,
}
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore
import org.lfdecentralizedtrust.splice.store.{
  AppStoreWithIngestion,
  Limit,
  MultiDomainAcsStore,
  UpdateHistory,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import org.lfdecentralizedtrust.splice.util.{Contract, ContractWithState, SpliceUtil}
import org.mockito.ArgumentMatchers.{any as javaAny, eq as javaEqTo}
import org.mockito.matchers.DefaultValueProvider
import org.scalatest.Assertion
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.Optional
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

/** Handler-side enforcement of the `maxItems` bounds declared on the scan request bodies. The
  * generated server does not validate them, so every bounded array needs an explicit check; these
  * tests pin one endpoint per request schema.
  */
class HttpScanHandlerRequestLimitsTest extends AnyWordSpec with BaseTest {

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val actorSystem: ActorSystem = ActorSystem("HttpScanHandlerRequestLimitsTest")
  implicit val tc: TraceContext = TraceContext.empty

  private val maxItems = HttpRequestLimits.MaxRequestArrayItems

  /** `NonEmptyVector` is a value class, so a matcher for it must yield a non-null instance: the
    * call site unboxes the argument before the mock ever sees it.
    */
  private implicit val anyPartyIds: DefaultValueProvider[NonEmptyVector[PartyId]] =
    new DefaultValueProvider[NonEmptyVector[PartyId]] {
      override def default: NonEmptyVector[PartyId] =
        NonEmptyVector.one(PartyId.tryFromProtoPrimitive("party::dummy"))
    }

  private val recordTime: OffsetDateTime =
    Instant.parse("2024-01-01T00:00:00Z").atOffset(ZoneOffset.UTC)
  private val recordTimeTs = CantonTimestamp.assertFromInstant(recordTime.toInstant)

  /** Distinct, parseable party IDs; `n` of them. */
  private def partyIds(n: Int): Vector[String] = (1 to n).map(i => s"party$i::dummy").toVector

  private def strings(n: Int): Vector[String] = (1 to n).map(i => s"item$i").toVector

  private def templates(n: Int): Vector[String] =
    (1 to n).map(i => s"package$i:Module:Entity").toVector

  private val instanceNames = SpliceInstanceNamesConfig(
    networkName = "network",
    networkFaviconUrl = "https://example.invalid/favicon",
    amuletName = "amulet",
    amuletNameAcronym = "A",
    nameServiceName = "name-service",
    nameServiceNameAcronym = "NS",
  )

  private def handler(
      scanStore: ScanStore = mock[ScanStore],
      snapshotStore: AcsSnapshotStore = mock[AcsSnapshotStore],
  ): HttpScanHandler = {
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
      snapshotStore = snapshotStore,
      eventStore = mock[ScanEventStore],
      bulkStorage = None,
      scanApiMetrics = new ScanHttpApiMetrics(NoOpMetricsFactory),
      dsoAnsResolver = mock[DsoAnsResolver],
      miningRoundsCacheTimeToLiveOverride = None,
      enableForcedAcsSnapshots = false,
      perAcsSnapshotTablesEnabled = true,
      clock = new SimClock(loggerFactory = NamedLoggerFactory.root),
      loggerFactory = NamedLoggerFactory.root,
      packageVersionSupport = mock[PackageVersionSupport],
      bftSequencers = Seq.empty,
      initialRound = "0",
      externalTransactionHashThresholdTime = None,
      updateHistoryMaxPageSize = 100,
      publicUrlO = None,
      lsuRollForwardConfigO = None,
    )
  }

  private def assertTooManyItems[A](f: => Future[A], fieldName: String, size: Int): Assertion = {
    // The check runs before the handler builds its Future, so it throws synchronously; the route
    // is evaluated inside HttpErrorHandler's exception handler either way.
    val ex = Try(f) match {
      case Failure(thrown) => thrown
      case Success(future) => future.failed.futureValue
    }
    inside(ex) { case grpcEx: StatusRuntimeException =>
      // INVALID_ARGUMENT is what HttpErrorHandler maps to HTTP 400.
      grpcEx.getStatus.getCode shouldBe Status.Code.INVALID_ARGUMENT
      grpcEx.getStatus.getDescription shouldBe
        s"Expected '$fieldName' to contain at most $maxItems items, but contained $size."
    }
  }

  private val emptySnapshotPage = QueryAcsSnapshotResult(
    migrationId = 0L,
    snapshotRecordTime = recordTimeTs,
    createdEventsInPage = Vector.empty,
    afterToken = None,
  )

  private def snapshotStoreReturningEmptyPage(): AcsSnapshotStore = {
    val snapshotStore = mock[AcsSnapshotStore]
    when(
      snapshotStore.queryAcsSnapshot(
        any[Long],
        any[CantonTimestamp],
        any[Option[AcsSnapshotStore.QueryAcsSnapshotPaginationToken]],
        any[Limit],
        any[Seq[PartyId]],
        any[Seq[org.lfdecentralizedtrust.splice.util.PackageQualifiedName]],
      )(any[TraceContext])
    ).thenReturn(Future.successful(emptySnapshotPage))
    when(
      snapshotStore.getHoldingsState(
        any[Long],
        any[CantonTimestamp],
        any[Option[AcsSnapshotStore.QueryAcsSnapshotPaginationToken]],
        any[Limit],
        any[NonEmptyVector[PartyId]],
      )(any[TraceContext])
    ).thenReturn(Future.successful(emptySnapshotPage))
    when(
      snapshotStore.getHoldingsSummary(
        any[Long],
        any[CantonTimestamp],
        any[NonEmptyVector[PartyId]],
        any[Long],
      )(any[TraceContext])
    ).thenReturn(
      Future.successful(
        HoldingsSummaryResult(
          migrationId = 0L,
          recordTime = recordTimeTs,
          asOfRound = 1L,
          summaries = Map.empty,
        )
      )
    )
    snapshotStore
  }

  "getOpenAndIssuingMiningRounds" should {
    "reject too many cached_open_mining_round_contract_ids" in {
      assertTooManyItems(
        handler().getOpenAndIssuingMiningRounds(
          ScanResource.GetOpenAndIssuingMiningRoundsResponse
        )(
          definitions.GetOpenAndIssuingMiningRoundsRequest(
            cachedOpenMiningRoundContractIds = strings(maxItems + 1),
            cachedIssuingRoundContractIds = Vector.empty,
          )
        )(TraceContext.empty),
        "cached_open_mining_round_contract_ids",
        maxItems + 1,
      )
    }

    "reject too many cached_issuing_round_contract_ids" in {
      assertTooManyItems(
        handler().getOpenAndIssuingMiningRounds(
          ScanResource.GetOpenAndIssuingMiningRoundsResponse
        )(
          definitions.GetOpenAndIssuingMiningRoundsRequest(
            cachedOpenMiningRoundContractIds = Vector.empty,
            cachedIssuingRoundContractIds = strings(maxItems + 1),
          )
        )(TraceContext.empty),
        "cached_issuing_round_contract_ids",
        maxItems + 1,
      )
    }

    "accept both arrays at exactly the limit" in {
      val h = handler(scanStore = scanStoreWithOneOpenRound())
      val response = h
        .getOpenAndIssuingMiningRounds(ScanResource.GetOpenAndIssuingMiningRoundsResponse)(
          definitions.GetOpenAndIssuingMiningRoundsRequest(
            cachedOpenMiningRoundContractIds = strings(maxItems),
            cachedIssuingRoundContractIds = strings(maxItems),
          )
        )(TraceContext.empty)
        .futureValue
      inside(response) { case ScanResource.GetOpenAndIssuingMiningRoundsResponseOK(value) =>
        value.openMiningRounds.keySet shouldBe Set(openRoundContractId)
      }
    }
  }

  "getAcsSnapshotAtV2" should {
    "reject too many party_ids" in {
      assertTooManyItems(
        handler().getAcsSnapshotAtV2(ScanResource.GetAcsSnapshotAtV2Response)(
          definitions.AcsRequestV2(
            migrationId = 0L,
            recordTime = recordTime,
            pageSize = 10,
            partyIds = Some(partyIds(maxItems + 1)),
          )
        )(TraceContext.empty),
        "party_ids",
        maxItems + 1,
      )
    }

    "reject too many templates" in {
      assertTooManyItems(
        handler().getAcsSnapshotAtV2(ScanResource.GetAcsSnapshotAtV2Response)(
          definitions.AcsRequestV2(
            migrationId = 0L,
            recordTime = recordTime,
            pageSize = 10,
            templates = Some(templates(maxItems + 1)),
          )
        )(TraceContext.empty),
        "templates",
        maxItems + 1,
      )
    }

    "accept both arrays at exactly the limit" in {
      val h = handler(snapshotStore = snapshotStoreReturningEmptyPage())
      h.getAcsSnapshotAtV2(ScanResource.GetAcsSnapshotAtV2Response)(
        definitions.AcsRequestV2(
          migrationId = 0L,
          recordTime = recordTime,
          pageSize = 10,
          partyIds = Some(partyIds(maxItems)),
          templates = Some(templates(maxItems)),
        )
      )(TraceContext.empty)
        .futureValue shouldBe a[ScanResource.GetAcsSnapshotAtV2ResponseOK]
    }
  }

  "getHoldingsStateAtV2" should {
    "reject too many owner_party_ids" in {
      assertTooManyItems(
        handler().getHoldingsStateAtV2(ScanResource.GetHoldingsStateAtV2Response)(
          definitions.HoldingsStateRequestV2(
            migrationId = 0L,
            recordTime = recordTime,
            pageSize = 10,
            ownerPartyIds = partyIds(maxItems + 1),
          )
        )(TraceContext.empty),
        "owner_party_ids",
        maxItems + 1,
      )
    }

    "accept owner_party_ids at exactly the limit" in {
      val h = handler(snapshotStore = snapshotStoreReturningEmptyPage())
      h.getHoldingsStateAtV2(ScanResource.GetHoldingsStateAtV2Response)(
        definitions.HoldingsStateRequestV2(
          migrationId = 0L,
          recordTime = recordTime,
          pageSize = 10,
          ownerPartyIds = partyIds(maxItems),
        )
      )(TraceContext.empty)
        .futureValue shouldBe a[ScanResource.GetHoldingsStateAtV2ResponseOK]
    }
  }

  "getHoldingsSummaryAt" should {
    "reject too many owner_party_ids" in {
      assertTooManyItems(
        handler().getHoldingsSummaryAt(ScanResource.GetHoldingsSummaryAtResponse)(
          definitions.HoldingsSummaryRequest(
            migrationId = 0L,
            recordTime = recordTime,
            ownerPartyIds = partyIds(maxItems + 1),
            asOfRound = Some(1L),
          )
        )(TraceContext.empty),
        "owner_party_ids",
        maxItems + 1,
      )
    }

    "accept owner_party_ids at exactly the limit" in {
      val h = handler(snapshotStore = snapshotStoreReturningEmptyPage())
      h.getHoldingsSummaryAt(ScanResource.GetHoldingsSummaryAtResponse)(
        definitions.HoldingsSummaryRequest(
          migrationId = 0L,
          recordTime = recordTime,
          ownerPartyIds = partyIds(maxItems),
          asOfRound = Some(1L),
        )
      )(TraceContext.empty)
        .futureValue shouldBe a[ScanResource.GetHoldingsSummaryAtResponseOK]
    }
  }

  "getHoldingsSummaryAtV1" should {
    "reject too many owner_party_ids" in {
      assertTooManyItems(
        handler().getHoldingsSummaryAtV1(ScanResource.GetHoldingsSummaryAtV1Response)(
          definitions.HoldingsSummaryRequestV1(
            migrationId = 0L,
            recordTime = recordTime,
            ownerPartyIds = partyIds(maxItems + 1),
          )
        )(TraceContext.empty),
        "owner_party_ids",
        maxItems + 1,
      )
    }

    "accept owner_party_ids at exactly the limit" in {
      val h = handler(snapshotStore = snapshotStoreReturningEmptyPage())
      h.getHoldingsSummaryAtV1(ScanResource.GetHoldingsSummaryAtV1Response)(
        definitions.HoldingsSummaryRequestV1(
          migrationId = 0L,
          recordTime = recordTime,
          ownerPartyIds = partyIds(maxItems),
        )
      )(TraceContext.empty)
        .futureValue shouldBe a[ScanResource.GetHoldingsSummaryAtV1ResponseOK]
    }
  }

  "listVoteRequestsByTrackingCid" should {
    "reject too many vote_request_contract_ids" in {
      assertTooManyItems(
        handler().listVoteRequestsByTrackingCid(
          definitions.BatchListVotesByVoteRequestsRequest(strings(maxItems + 1))
        ),
        "vote_request_contract_ids",
        maxItems + 1,
      )
    }

    "accept vote_request_contract_ids at exactly the limit" in {
      val scanStore = mock[ScanStore]
      when(
        scanStore.listVoteRequestsByTrackingCid(any[Seq[VoteRequest.ContractId]], any[Limit])(
          any[TraceContext]
        )
      ).thenReturn(Future.successful(Seq.empty))
      handler(scanStore = scanStore)
        .listVoteRequestsByTrackingCid(
          definitions.BatchListVotesByVoteRequestsRequest(strings(maxItems))
        )
        .futureValue
        .voteRequests shouldBe empty
    }
  }

  private val openRoundContractId = "open-round-1"

  /** Enough of a store for `getOpenAndIssuingMiningRounds` to produce a response: the TTL
    * computation picks the smallest tick duration of all non-closed rounds and throws on an empty
    * set, so at least one round is needed.
    */
  private def scanStoreWithOneOpenRound(): ScanStore = {
    val scanStore = mock[ScanStore]
    val acsStore = mock[MultiDomainAcsStore]
    when(scanStore.multiDomainAcsStore).thenReturn(acsStore)
    stubListContracts(acsStore, OpenMiningRound.COMPANION, Seq(openRound))
    stubListContracts(
      acsStore,
      IssuingMiningRound.COMPANION,
      Seq.empty[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
    )
    stubListContracts(
      acsStore,
      SummarizingMiningRound.COMPANION,
      Seq.empty[ContractWithState[SummarizingMiningRound.ContractId, SummarizingMiningRound]],
    )
    scanStore
  }

  /** The type parameters of `listContracts` are inferred from its implicit `ContractCompanion`,
    * which a matcher cannot supply, so they are pinned here instead.
    */
  private def stubListContracts[C, TCid <: ContractId[?], T](
      acsStore: MultiDomainAcsStore,
      companion: C,
      result: Seq[ContractWithState[TCid, T]],
  ): Unit = {
    when(
      acsStore.listContracts[C, TCid, T](javaEqTo(companion), javaAny[Limit]())(
        javaAny[MultiDomainAcsStore.ContractCompanion[C, TCid, T]](),
        javaAny[TraceContext](),
      )
    ).thenReturn(Future.successful(result))
    ()
  }

  private lazy val openRound: ContractWithState[OpenMiningRound.ContractId, OpenMiningRound] = {
    val opensAt = Instant.parse("2024-01-01T00:00:00Z").truncatedTo(ChronoUnit.MICROS)
    val payload = new OpenMiningRound(
      "dso::dummy",
      new Round(1L),
      SpliceUtil.damlDecimal(1.0),
      opensAt,
      opensAt.plusSeconds(600),
      new RelTime(600L * 1000000L),
      SpliceUtil.defaultTransferConfig(10, SpliceUtil.damlDecimal(1.0)),
      new IssuanceConfig(
        SpliceUtil.damlDecimal(10000000000.0),
        SpliceUtil.damlDecimal(0.0),
        SpliceUtil.damlDecimal(0.62),
        SpliceUtil.damlDecimal(0.0),
        SpliceUtil.damlDecimal(1.5),
        SpliceUtil.damlDecimal(0.6),
        Optional.empty(),
        Optional.empty(),
      ),
      new RelTime(600L * 1000000L),
      Optional.empty(),
      Optional.empty(),
    )
    ContractWithState(
      Contract(
        OpenMiningRound.TEMPLATE_ID_WITH_PACKAGE_ID,
        new OpenMiningRound.ContractId(openRoundContractId),
        payload,
        ByteString.EMPTY,
        Instant.EPOCH,
      ),
      ContractState.InFlight,
    )
  }
}

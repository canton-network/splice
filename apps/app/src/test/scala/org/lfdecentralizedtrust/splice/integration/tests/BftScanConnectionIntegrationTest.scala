// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import cats.data.{NonEmptyList, OptionT}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.http.v0.wallet as http
import org.lfdecentralizedtrust.splice.http.v0.wallet.AcceptTokenStandardTransferResponse
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.store.ValidatorConfigProvider.ScanUrlInternalConfig
import org.lfdecentralizedtrust.tokenstandard.{
  allocation,
  allocationinstruction,
  metadata,
  transferinstruction,
}
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.GetTransferInstructionAcceptContextResponse
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.definitions.GetChoiceContextRequest
import org.slf4j.event.Level

import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class BftScanConnectionIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with SvTestUtil
    with HasExecutionContext
    with HasActorSystem {

  override protected def runEventHistorySanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs {
          case (name, c) if name == "aliceValidator" =>
            val dbEnabledConfig = BftScanClientConfig.Bft(
              seedUrls = NonEmptyList.of(
                Uri("http://127.0.0.1:5012")
              ),
              scansRefreshInterval = NonNegativeFiniteDuration.ofSeconds(60),
            )
            c.copy(scanClient = dbEnabledConfig)
          case (_, c) => c
        }(config)
      )
      .withManualStart

  "init fast enough even if there are unavailable scans" in { implicit env =>
    startAllSync(
      sv1ScanBackend,
      sv1ValidatorBackend,
      sv1Backend,
      sv2ScanBackend,
      sv2ValidatorBackend,
      sv2Backend,
    )

    // make sure both scans get registered
    eventually() {
      val dsoInfo = sv1Backend.getDsoInfo()
      val scans = for {
        (_, nodeState) <- dsoInfo.svNodeStates
        (_, synchronizerNode) <- nodeState.payload.state.synchronizerNodes.asScala
        scan <- synchronizerNode.scan.toScala
      } yield scan
      scans should have size 2 // sv1&2's scans
    }

    // Alice's validator will see the two scans, but SV2's won't connect
    sv2ScanBackend.stop()
    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
      {
        aliceValidatorBackend.startSync()
        aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
      },
      forAll(_)(
        _.message should (include(
          s"Failed to connect to scan of ${getSvName(2)} (http://localhost:5112)."
        ) or
          include("Encountered 4 consecutive transient failures") or include(
            "Failed to connect to scan of FAILED Seed URL #0 (http://localhost:5112)."
          ))
      ),
    )

    eventuallySucceeds() {
      aliceAnsExternalClient.listAnsEntries()
    }
  }

  "serve all token standard endpoints via the scan-proxy" in { implicit env =>
    startAllSync(
      sv1ScanBackend,
      sv1Backend,
      sv2ScanBackend,
      sv2Backend,
      sv3ScanBackend,
      sv3Backend,
      sv4ScanBackend,
      sv4Backend,
    )

    aliceValidatorBackend.startSync()
    onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)
    val headers = List(
      Authorization(
        OAuth2BearerToken(aliceValidatorWalletClient.token.valueOrFail("No token found"))
      )
    )

    val scanProxyUrl =
      s"http://${aliceValidatorBackend.config.adminApi.address}:${aliceValidatorBackend.config.adminApi.port.unwrap}/api/validator/v0/scan-proxy"
    val send: HttpRequest => Future[HttpResponse] = Http().singleRequest(_)
    val fakeCid = "00" + "01" * 31 + "42"
    // Invalid choice arguments: factories are expected to reject them with a 400.
    val emptyChoiceArgs = io.circe.Json.obj()

    val metadataClient = metadata.v1.Client.httpClient(send, scanProxyUrl)
    val transferV1Client = transferinstruction.v1.Client.httpClient(send, scanProxyUrl)
    val transferV2Client = transferinstruction.v2.Client.httpClient(send, scanProxyUrl)
    val allocationInstructionV1Client =
      allocationinstruction.v1.Client.httpClient(send, scanProxyUrl)
    val allocationInstructionV2Client =
      allocationinstruction.v2.Client.httpClient(send, scanProxyUrl)
    val allocationV1Client = allocation.v1.Client.httpClient(send, scanProxyUrl)
    val allocationV2Client = allocation.v2.Client.httpClient(send, scanProxyUrl)

    def check[R](endpoint: String)(response: => Either[Either[Throwable, HttpResponse], R])(
        expected: PartialFunction[R, Unit]
    ) =
      withClue(s"$endpoint via scan-proxy") {
        inside(response) { case Right(r) => inside(r)(expected) }
      }

    // We just need to check that the responses can be parsed to know that they're wired correctly

    // metadata v1
    check("GET /registry/metadata/v1/info")(
      metadataClient.getRegistryInfo(headers).value.futureValue
    ) { case metadata.v1.GetRegistryInfoResponse.OK(info) =>
      info.adminId shouldBe dsoParty.toProtoPrimitive
    }
    check("GET /registry/metadata/v1/instruments")(
      metadataClient.listInstruments(None, None, headers).value.futureValue
    ) { case metadata.v1.ListInstrumentsResponse.OK(instruments) =>
      instruments.instruments.map(_.id) shouldBe Vector("Amulet")
    }
    check("GET /registry/metadata/v1/instruments/{instrumentId}")(
      metadataClient.getInstrument("Amulet", headers).value.futureValue
    ) { case metadata.v1.GetInstrumentResponse.OK(instrument) =>
      instrument.id shouldBe "Amulet"
    }

    // transfer-instruction v1
    check("POST /registry/transfer-instruction/v1/transfer-factory")(
      transferV1Client
        .getTransferFactory(
          transferinstruction.v1.definitions.GetFactoryRequest(emptyChoiceArgs, None),
          headers,
        )
        .value
        .futureValue
    ) { case transferinstruction.v1.GetTransferFactoryResponse.BadRequest(_) => }
    check("POST /registry/transfer-instruction/v1/{id}/choice-contexts/accept")(
      transferV1Client
        .getTransferInstructionAcceptContext(fakeCid, GetChoiceContextRequest(None), headers)
        .value
        .futureValue
    ) { case transferinstruction.v1.GetTransferInstructionAcceptContextResponse.NotFound(_) => }
    check("POST /registry/transfer-instruction/v1/{id}/choice-contexts/reject")(
      transferV1Client
        .getTransferInstructionRejectContext(fakeCid, GetChoiceContextRequest(None), headers)
        .value
        .futureValue
    ) { case transferinstruction.v1.GetTransferInstructionRejectContextResponse.NotFound(_) => }
    check("POST /registry/transfer-instruction/v1/{id}/choice-contexts/withdraw")(
      transferV1Client
        .getTransferInstructionWithdrawContext(fakeCid, GetChoiceContextRequest(None), headers)
        .value
        .futureValue
    ) { case transferinstruction.v1.GetTransferInstructionWithdrawContextResponse.NotFound(_) => }

    // transfer-instruction v2
    check("POST /registry/transfer-instruction/v2/transfer-factory")(
      transferV2Client
        .getTransferFactory(
          transferinstruction.v2.definitions.GetFactoryRequest(emptyChoiceArgs, None),
          headers,
        )
        .value
        .futureValue
    ) { case transferinstruction.v2.GetTransferFactoryResponse.BadRequest(_) => }
    check("POST /registry/transfer-instruction/v2/{id}/choice-contexts/accept")(
      transferV2Client
        .getTransferInstructionAcceptContext(
          fakeCid,
          transferinstruction.v2.definitions.GetChoiceContextRequest(None),
          headers,
        )
        .value
        .futureValue
    ) { case transferinstruction.v2.GetTransferInstructionAcceptContextResponse.NotFound(_) => }
    check("POST /registry/transfer-instruction/v2/{id}/choice-contexts/reject")(
      transferV2Client
        .getTransferInstructionRejectContext(
          fakeCid,
          transferinstruction.v2.definitions.GetChoiceContextRequest(None),
          headers,
        )
        .value
        .futureValue
    ) { case transferinstruction.v2.GetTransferInstructionRejectContextResponse.NotFound(_) => }
    check("POST /registry/transfer-instruction/v2/{id}/choice-contexts/withdraw")(
      transferV2Client
        .getTransferInstructionWithdrawContext(
          fakeCid,
          transferinstruction.v2.definitions.GetChoiceContextRequest(None),
          headers,
        )
        .value
        .futureValue
    ) { case transferinstruction.v2.GetTransferInstructionWithdrawContextResponse.NotFound(_) => }

    // allocation-instruction v1
    // Unlike the other factory endpoints, the v1 allocation factory does not parse the choice
    // arguments and always returns the factory with its choice context.
    check("POST /registry/allocation-instruction/v1/allocation-factory")(
      allocationInstructionV1Client
        .getAllocationFactory(
          allocationinstruction.v1.definitions.GetFactoryRequest(emptyChoiceArgs, None),
          headers,
        )
        .value
        .futureValue
    ) { case allocationinstruction.v1.GetAllocationFactoryResponse.OK(_) => }

    // allocation-instruction v2
    // Choice contexts are always rejected: amulet never creates allocation instructions.
    check("POST /registry/allocation-instruction/v2/allocation-factory")(
      allocationInstructionV2Client
        .getAllocationFactory(
          allocationinstruction.v2.definitions.GetFactoryRequest(emptyChoiceArgs, None),
          headers,
        )
        .value
        .futureValue
    ) { case allocationinstruction.v2.GetAllocationFactoryResponse.BadRequest(_) => }
    check("POST /registry/allocation-instruction/v2/{id}/choice-contexts/accept")(
      allocationInstructionV2Client
        .getAllocationInstructionAcceptContext(
          fakeCid,
          allocationinstruction.v2.definitions.GetChoiceContextRequest(None),
          headers,
        )
        .value
        .futureValue
    ) { case allocationinstruction.v2.GetAllocationInstructionAcceptContextResponse.BadRequest(_) =>
    }
    check("POST /registry/allocation-instruction/v2/{id}/choice-contexts/withdraw")(
      allocationInstructionV2Client
        .getAllocationInstructionWithdrawContext(
          fakeCid,
          allocationinstruction.v2.definitions.GetChoiceContextRequest(None),
          headers,
        )
        .value
        .futureValue
    ) {
      case allocationinstruction.v2.GetAllocationInstructionWithdrawContextResponse.BadRequest(_) =>
    }

    // allocation v1
    check("POST /registry/allocations/v1/{id}/choice-contexts/execute-transfer")(
      allocationV1Client
        .getAllocationTransferContext(
          fakeCid,
          allocation.v1.definitions.GetChoiceContextRequest(None),
          headers,
        )
        .value
        .futureValue
    ) { case allocation.v1.GetAllocationTransferContextResponse.NotFound(_) => }
    check("POST /registry/allocations/v1/{id}/choice-contexts/withdraw")(
      allocationV1Client
        .getAllocationWithdrawContext(
          fakeCid,
          allocation.v1.definitions.GetChoiceContextRequest(None),
          headers,
        )
        .value
        .futureValue
    ) { case allocation.v1.GetAllocationWithdrawContextResponse.NotFound(_) => }
    check("POST /registry/allocations/v1/{id}/choice-contexts/cancel")(
      allocationV1Client
        .getAllocationCancelContext(
          fakeCid,
          allocation.v1.definitions.GetChoiceContextRequest(None),
          headers,
        )
        .value
        .futureValue
    ) { case allocation.v1.GetAllocationCancelContextResponse.NotFound(_) => }

    // allocation v2
    check("POST /registry/allocation/v2/settlement-factory")(
      allocationV2Client
        .getSettlementFactory(
          allocation.v2.definitions.GetFactoryRequest(emptyChoiceArgs, None),
          headers,
        )
        .value
        .futureValue
    ) { case allocation.v2.GetSettlementFactoryResponse.BadRequest(_) => }
    check("POST /registry/allocations/v2/{id}/choice-contexts/withdraw")(
      allocationV2Client
        .getAllocationWithdrawContext(
          fakeCid,
          allocation.v2.definitions.GetChoiceContextRequest(None),
          headers,
        )
        .value
        .futureValue
    ) { case allocation.v2.GetAllocationWithdrawContextResponse.NotFound(_) => }
    check("POST /registry/allocations/v2/{id}/choice-contexts/cancel")(
      allocationV2Client
        .getAllocationCancelContext(
          fakeCid,
          allocation.v2.definitions.GetChoiceContextRequest(None),
          headers,
        )
        .value
        .futureValue
    ) { case allocation.v2.GetAllocationCancelContextResponse.NotFound(_) => }
  }

  "agree on failed HttpCommandException" in { implicit env =>
    startAllSync(
      sv1ScanBackend,
      sv1Backend,
      sv2ScanBackend,
      sv2Backend,
      sv3ScanBackend,
      sv3Backend,
      sv4ScanBackend,
      sv4Backend,
    )

    aliceValidatorBackend.startSync()
    onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)
    val walletUserToken =
      OAuth2BearerToken(aliceValidatorWalletClient.token.valueOrFail("No token found"))

    val fakeCid = new TransferInstruction.ContractId("00" + s"01" * 31 + "42")

    val singleScanClient = transferinstruction.v1.Client
      .httpClient(
        Http().singleRequest(_),
        s"http://${sv1ScanBackend.config.adminApi.address}:${sv1ScanBackend.config.adminApi.port.unwrap}",
      )

    val singleScanResponse =
      singleScanClient
        .getTransferInstructionAcceptContext(fakeCid.contractId, GetChoiceContextRequest(None))
        .value
        .futureValue

    val bftClient = transferinstruction.v1.Client
      .httpClient(
        Http().singleRequest(_),
        s"http://${aliceValidatorBackend.config.adminApi.address}:${aliceValidatorBackend.config.adminApi.port.unwrap}/api/validator/v0/scan-proxy",
      )
    val bftResponse = bftClient
      .getTransferInstructionAcceptContext(
        fakeCid.contractId,
        GetChoiceContextRequest(None),
        List(Authorization(walletUserToken)),
      )
      .value
      .futureValue

    inside((bftResponse, singleScanResponse)) {
      case (
            Right(err1),
            Right(err2),
          ) =>
        err1 should be(err2)
    }

    val walletClient = http.WalletClient.httpClient(
      Http().singleRequest(_),
      s"http://${aliceValidatorBackend.config.adminApi.address}:${aliceValidatorBackend.config.adminApi.port.unwrap}",
    )

    val endpointThatUsesBftCallResult =
      walletClient
        .acceptTokenStandardTransfer(
          fakeCid.contractId,
          List(Authorization(walletUserToken)),
        )
        .value
        .futureValue

    inside((endpointThatUsesBftCallResult, singleScanResponse)) {
      case (
            Right(AcceptTokenStandardTransferResponse.NotFound(err1)),
            Right(GetTransferInstructionAcceptContextResponse.NotFound(err2)),
          ) =>
        // still different types...
        err1.error should be(err2.error)
    }
  }

  "validator onboarding and recovery succeed with internal config turned on" in { implicit env =>
    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      sv2Backend,
      sv2ScanBackend,
      sv3Backend,
      sv3ScanBackend,
      sv4Backend,
      sv4ScanBackend,
    )

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        aliceValidatorBackend.startSync()
      },
      logs => {
        val aliceValidatorLogs = logs.filter(_.loggerName.contains("validator=aliceValidator"))
        val messages = aliceValidatorLogs.map(_.message)
        withClue("Validator should first bootstrap with 1 and then 4 scans") {
          messages.filter(_.contains(bootstrapsWith1UrlLog)) should have length 1
          messages.filter(_.contains(bootstrapsWith4UrlsLog)) should have length 1
        }.withClue(
          s"Actual Logs: \n ${messages.filter(_.contains(bootstrapsWith1UrlLog))} \n ${messages
              .filter(_.contains(bootstrapsWith4UrlsLog))}"
        )
      },
    )

    val persistedState: OptionT[Future, Seq[ScanUrlInternalConfig]] =
      aliceValidatorBackend.appState.configProvider.getScanUrlInternalConfig()

    val expectedConfigs = Seq(
      ScanUrlInternalConfig(getSvName(1), "http://localhost:5012"),
      ScanUrlInternalConfig(getSvName(2), "http://localhost:5112"),
      ScanUrlInternalConfig(getSvName(3), "http://localhost:5212"),
      ScanUrlInternalConfig(getSvName(4), "http://localhost:5312"),
    )

    withClue("Persisted state should contain the expected internal scan configurations") {
      persistedState.value.futureValue.value should contain theSameElementsAs expectedConfigs withClue "ScanUrlInternalConfig"
    }

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        aliceValidatorBackend.stop()
        aliceValidatorBackend.startSync()
      },
      logs => {
        val aliceValidatorLogs = logs.filter(_.loggerName.contains("validator=aliceValidator"))
        val messages = aliceValidatorLogs.map(_.message)
        withClue(
          "Validator should bootstrap with all the scan urls persisted to the internal store"
        ) {
          messages.filter(_.contains(bootstrapsWith1UrlLog)) should have length 0
          messages.filter(_.contains(bootstrapsWith4UrlsLog)) should have length 2
        }.withClue(
          s"Actual Logs: \n ${messages.filter(_.contains(bootstrapsWith1UrlLog))} \n ${messages
              .filter(_.contains(bootstrapsWith4UrlsLog))} "
        )
      },
    )

    withClue("Alice's validator should be able to onboard a user after establishing connections.") {
      eventuallySucceeds() {
        aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
      }
    }

  }

  "validator reboots when initial bootstrap scan is offline" in { implicit env =>
    clue("Initialize the DSO") {
      initDso()
    }

    clue("Start Alice validator") {
      aliceValidatorBackend.startSync()
    }

    clue("Stop SV1 scan") {
      sv1ScanBackend.stop()
    }

    clue("Stop Alice validator") {
      aliceValidatorBackend.stop()
    }

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        // need to supress due to connection attempts to failed scan of Sv1
        aliceValidatorBackend.startSync()
        aliceValidatorBackend.onboardUser("Test")
      },
      _ => succeed,
    )
  }

  private val bootstrapsWith1UrlLog =
    s"Validator bootstrapping with 1 seed URLs: List(http://127.0.0.1:5012)"

  private val bootstrapsWith4UrlsLog =
    s"Validator bootstrapping with 4 seed URLs: List(http://localhost:5012, http://localhost:5112, http://localhost:5212, http://localhost:5312)"

}

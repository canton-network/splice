package org.lfdecentralizedtrust.splice.integration.tests

import io.circe.parser.decode
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.client.RequestBuilding.Get
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import org.lfdecentralizedtrust.splice.auth.AuthUtil
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{FrontendLoginUtil, WalletFrontendTestUtil}
import com.digitalasset.canton.http.json.v2.JsStateServiceCodecs.*
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.version.ProtocolVersion
import com.daml.ledger.api.v2.state_service.GetConnectedSynchronizersResponse
import com.daml.ledger.api.v2.transaction_filter.CumulativeFilter.IdentifierFilter
import com.daml.ledger.api.v2.transaction_filter.{
  CumulativeFilter,
  EventFormat,
  Filters,
  WildcardFilter,
}
import monocle.Monocle.toAppliedFocusOps
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.test.dummyholding.DummyHolding
import org.lfdecentralizedtrust.splice.util.JavaDecodeUtil

import java.nio.file.Paths
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

class LocalNetFrontendIntegrationTest
    extends FrontendIntegrationTestWithIsolatedEnvironment("frontend")
    with FrontendLoginUtil
    with WalletFrontendTestUtil {
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(Seq("localnet-topology.conf"), this.getClass.getSimpleName)
      .updateTestingConfig(
        _.focus(_.participantsWithoutLapiVerification).replace(
          Set(
            "app-provider",
            "app-user",
          )
        )
      )
      .withManualStart

  // This does nothing as the wallet clients will not actually be connected to the compose setup
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override lazy val resetRequiredTopologyState = false

  val partyHint = "localnet-localparty-1"

  // The user all localnet nodes use for their ledger API access, see
  // cluster/compose/localnet/env/*-auth-on.env
  private val ledgerApiUserId = "ledger-api-user"

  private def withLocalNet(
      additionalArgs: Seq[String]
  )(f: FixtureParam => Any)(implicit env: FixtureParam): Unit =
    try {
      val ret = (Seq("build-tools/splice-localnet-compose.sh", "start") ++ additionalArgs).!
      if (ret != 0) {
        fail("Failed to start docker-compose SV and validator")
      }
      f(env)
    } finally {
      (Seq("build-tools/splice-localnet-compose.sh", "stop", "-D") ++ additionalArgs).!
    }

  private def testValidators(implicit env: FixtureParam): Unit =
    clue("Test validators") {
      List(
        ("app-user", 2000, "app_user"),
        ("app-provider", 3000, "app_provider"),
      ).foreach { case (user, port, partyHintPrefix) =>
        clue(s"Test $user validator") {
          val host = "wallet.localhost"
          val url = s"http://$host:$port"
          withFrontEnd("frontend") { implicit webDriver =>
            eventuallySucceeds()(go to url)
            actAndCheck(timeUntilSuccess = 60.seconds)(
              s"Login as $user",
              login(port, user, host),
            )(
              s"$user is already onboarded",
              _ =>
                seleniumText(find(id("logged-in-user"))) should startWith(
                  s"${partyHintPrefix}_$partyHint"
                ),
            )
            // Wait for some traffic to be bought before proceeding so that we don't hit a "traffic below reserved amount" error
            waitForTrafficPurchase()
            go to url
            actAndCheck(
              s"Login as $user",
              loginOnCurrentPage(port, user, host),
            )(
              s"$user is already onboarded",
              _ =>
                seleniumText(find(id("logged-in-user"))) should startWith(
                  s"${partyHintPrefix}_$partyHint"
                ),
            )
            tapAmulets(345.6)
          }
        }
      }
    }

  private def testSvUi(): Unit =
    clue("Basic test of SV UIs") {
      withFrontEnd("frontend") { implicit webDriver =>
        actAndCheck(
          "Open the Scan UI",
          // The scheme is required: without it Firefox parses `scan.localhost:` as a
          // (non-special) URL scheme and refuses to navigate.
          go to "http://scan.localhost:4000",
        )(
          "Open rounds should be listed",
          _ => findAll(className("open-mining-round-row")) should have length 2,
        )

        actAndCheck(timeUntilSuccess = 60.seconds)(
          "Login to the wallet as sv",
          login(4000, "sv", "wallet.localhost"),
        )(
          "sv is already onboarded",
          _ => seleniumText(find(id("logged-in-user"))) should startWith("sv.sv.ans"),
        )

        actAndCheck()(
          "Login to the SV app as sv",
          login(4000, "sv", "sv.localhost"),
        )(
          "sv is already onboarded, and the app is working",
          _ => {
            seleniumText(
              find(id("svUser")).value
                .childElement(className("general-dso-value-name"))
            ) should be("ledger-api-user")
          },
        )
      }
    }

  private val token = AuthUtil.testToken(AuthUtil.testAudience, ledgerApiUserId, "unsafe")

  private val dummyHoldingDarPath = Paths
    .get(
      "token-standard/examples/splice-token-test-dummy-holding/.daml/dist/splice-token-test-dummy-holding-current.dar"
    )
    .toAbsolutePath
    .toString

  private def testTokenStandardApi(implicit env: FixtureParam): Unit =
    clue("Test token standard APIs") {
      val registryInfo = scancl("scanClient").getRegistryInfo()
      registryInfo.adminId should startWith("DSO::")
      val userRegistryInfo =
        vc("userValidatorClient").copy(token = Some(token)).scanProxy.getRegistryInfo()
      val providerRegistryInfo =
        vc("providerValidatorClient").copy(token = Some(token)).scanProxy.getRegistryInfo()
      registryInfo shouldBe userRegistryInfo
      registryInfo shouldBe providerRegistryInfo
    }

  private def testMultiSynchronizerSupport(isMultiSync: Boolean)(implicit env: FixtureParam): Unit =
    clue("Test multi-synchronizer support") {
      List(
        ("app-user", 2000),
        ("app-provider", 3000),
      ).foreach { case (user, port) =>
        clue(s"Test $user validator") {
          implicit val actorSystem: ActorSystem = env.actorSystem
          registerHttpConnectionPoolsCleanup(env)
          val host = "json-ledger-api.localhost"
          val url = s"http://$host:$port"

          val response =
            Http()
              .singleRequest(
                Get(s"$url/v2/state/connected-synchronizers")
                  .withHeaders(Seq(Authorization(OAuth2BearerToken(token))))
              )
              .futureValue
          response.status should be(StatusCodes.OK)
          val payload = response.entity.toStrict(10.seconds).futureValue.data.utf8String
          val message = decode[GetConnectedSynchronizersResponse](payload).getOrElse(
            fail("Failed to decode response from /v2/state/connected-synchronizers")
          )
          val synchronizers = message.connectedSynchronizers.map(_.synchronizerAlias)
          if (isMultiSync)
            synchronizers should contain allOf ("global", "app-synchronizer")
          else
            synchronizers should contain only "global"
        }
      }
    }

  private def participantClient(name: String)(implicit env: FixtureParam) = {
    val remoteParticipant =
      env.participants.remote
        .find(_.name == name)
        .getOrElse(fail(s"$name participant not found"))
    new ParticipantClientReference(
      env,
      remoteParticipant.name,
      remoteParticipant.config.copy(token = Some(token)),
    )
  }

  private def synchronizerId(
      participant: ParticipantClientReference,
      alias: String,
  ): SynchronizerId =
    participant.synchronizers
      .list_connected()
      .find(_.synchronizerAlias.unwrap == alias)
      .getOrElse(fail(s"${participant.name} is not connected to $alias"))
      .synchronizerId

  private def testReassignment(participantName: String, validatorClientName: String)(implicit
      env: FixtureParam
  ): Unit =
    clue(s"Reassign a contract between global and app-synchronizer on $participantName") {
      val participant = participantClient(participantName)
      val party = vc(validatorClientName).copy(token = Some(token)).getValidatorPartyId()
      val globalSynchronizerId = synchronizerId(participant, "global")
      val appSynchronizerId = synchronizerId(participant, "app-synchronizer")

      participant.upload_dar_unless_exists(dummyHoldingDarPath)

      val createdContract = clue("Create a DummyHolding on the global synchronizer") {
        val tx = participant.ledger_api_extensions.commands.submitJava(
          actAs = Seq(party),
          commands = new DummyHolding(
            party.toProtoPrimitive,
            party.toProtoPrimitive,
            BigDecimal(42).bigDecimal,
          ).create().commands().asScala.toSeq,
          synchronizerId = Some(globalSynchronizerId),
          userId = ledgerApiUserId,
        )
        JavaDecodeUtil.decodeAllCreated(DummyHolding.COMPANION)(tx).loneElement
      }
      val contractId = createdContract.id.contractId
      val lfContractId = LfContractId.assertFromString(contractId)

      def synchronizerOfContract() =
        participant.ledger_api_extensions.acs
          .lookup_contract_domain(party, Set(contractId))
          .get(contractId)

      synchronizerOfContract() should be(Some(globalSynchronizerId))

      val eventFormat = Some(
        EventFormat(
          filtersByParty = Map(
            party.toProtoPrimitive -> Filters(
              Seq(
                CumulativeFilter(
                  IdentifierFilter.WildcardFilter(
                    WildcardFilter(includeCreatedEventBlob = false)
                  )
                )
              )
            )
          ),
          filtersForAnyParty = None,
          verbose = true,
        )
      )

       def reassign(source: SynchronizerId, target: SynchronizerId): Unit = {
        val unassigned = participant.ledger_api.commands
          .submit_unassign_with_format(
            submitter = party,
            contractIds = Seq(lfContractId),
            source = source,
            target = target,
            userId = ledgerApiUserId,
            eventFormat = eventFormat,
            timeout = None,
          )
          .unassignedWrapper
        val _ = participant.ledger_api.commands.submit_assign_with_format(
          submitter = party,
          reassignmentId = unassigned.reassignmentId,
          source = source,
          target = target,
          userId = ledgerApiUserId,
          eventFormat = eventFormat,
          timeout = None,
        )
      }

      actAndCheck(
        "Reassign the contract to the app-synchronizer",
        reassign(globalSynchronizerId, appSynchronizerId),
      )(
        "The contract is now assigned to the app-synchronizer",
        _ => synchronizerOfContract() should be(Some(appSynchronizerId)),
      )

      actAndCheck(
        "Reassign the contract back to the global synchronizer",
        reassign(appSynchronizerId, globalSynchronizerId),
      )(
        "The contract is assigned to the global synchronizer again",
        _ => synchronizerOfContract() should be(Some(globalSynchronizerId)),
      )
    }

  "docker-compose based localnet works for single synchronizer" in { implicit env =>
    withLocalNet(Seq.empty) { implicit env =>
      testValidators
      testSvUi()
      testTokenStandardApi
      testMultiSynchronizerSupport(isMultiSync = false)
    }
  }

  "docker-compose based localnet works for multiple synchronizers" in { implicit env =>
    withLocalNet(Seq("-M")) { implicit env =>
      testMultiSynchronizerSupport(isMultiSync = true)
      testReassignment("app-provider", "providerValidatorClient")
      testReassignment("app-user", "userValidatorClient")
    }
  }

  "localnet supports configurable protocol versions" in { implicit env =>
    withLocalNet(Seq("-u", "-p", "35")) { implicit env =>
      val appProviderParticipant = participantClient("app-provider")
      appProviderParticipant.synchronizers
        .list_connected()
        .loneElement
        .physicalSynchronizerId
        .protocolVersion shouldBe ProtocolVersion.v35
    }
  }
}

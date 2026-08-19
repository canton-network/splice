package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.transaction_filter.CumulativeFilter.IdentifierFilter
import com.daml.ledger.api.v2.transaction_filter.{
  CumulativeFilter,
  EventFormat,
  Filters,
  WildcardFilter,
}
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.topology.SynchronizerId
import monocle.Monocle.toAppliedFocusOps
import org.lfdecentralizedtrust.splice.auth.AuthUtil
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.test.dummyholding.DummyHolding
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.util.JavaDecodeUtil

import java.nio.file.Paths
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

/** Verifies the actual capability unlocked by the multi-synchronizer topology feature flag set in
  * cluster/compose/localnet/conf/console/app-synchronizer.sc: reassigning a contract between the
  * global synchronizer and the app-synchronizer. Without the flag, the unassignment is rejected
  * with MultiSynchronizerIsNotEnabled.
  *
  * This spins up the docker-compose localnet with the `multi-sync` profile enabled (-M)
  */
class LocalNetReassignIntegrationTest extends IntegrationTestWithIsolatedEnvironment {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(Seq("localnet-reassign-topology.conf"), this.getClass.getSimpleName)
      .updateTestingConfig(
        _.focus(_.participantsWithoutLapiVerification).replace(
          Set(
            "app-provider",
            "app-user",
          )
        )
      )
      .withManualStart

  // These do nothing as the clients will not actually be connected to the compose setup.
  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override lazy val resetRequiredTopologyState = false

  // The user all localnet nodes use for their ledger API access, see
  // cluster/compose/localnet/env/*-auth-on.env
  private val ledgerApiUserId = "ledger-api-user"

  private val token = AuthUtil.testToken(AuthUtil.testAudience, ledgerApiUserId, "unsafe")

  private val dummyHoldingDarPath = Paths
    .get(
      "token-standard/examples/splice-token-test-dummy-holding/.daml/dist/splice-token-test-dummy-holding-current.dar"
    )
    .toAbsolutePath
    .toString

  private def withLocalNet(
  )(f: FixtureParam => Any)(implicit env: FixtureParam): Unit =
    try {
      val ret = (Seq("build-tools/splice-localnet-compose.sh", "start") ++ Seq("-M")).!
      if (ret != 0) {
        fail("Failed to start docker-compose SV and validator")
      }
      f(env)
    } finally {
      (Seq("build-tools/splice-localnet-compose.sh", "stop", "-D") ++ Seq("-M")).!
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

      def contractSynchronizerId(): Option[String] =
        participant.ledger_api.state.acs
          .active_contracts_of_party(party = party)
          .find(_.createdEvent.value.contractId == contractId)
          .map(_.synchronizerId)

      contractSynchronizerId() shouldBe Some(globalSynchronizerId.toProtoPrimitive)

      // Scope the reassignment event format to `party`
      val eventFormat =
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

      def reassign(source: SynchronizerId, target: SynchronizerId): Unit = {
        val unassigned = participant.ledger_api.commands
          .submit_unassign_with_format(
            submitter = party,
            contractIds = Seq(lfContractId),
            source = source,
            target = target,
            userId = ledgerApiUserId,
            eventFormat = Some(eventFormat),
            timeout = None,
          )
          .unassignedWrapper
        val _ = participant.ledger_api.commands.submit_assign_with_format(
          submitter = party,
          reassignmentId = unassigned.reassignmentId,
          source = source,
          target = target,
          userId = ledgerApiUserId,
          eventFormat = Some(eventFormat),
          timeout = None,
        )
      }

      actAndCheck(
        "Reassign the contract to the app-synchronizer",
        reassign(globalSynchronizerId, appSynchronizerId),
      )(
        "The contract is now assigned to the app-synchronizer",
        _ => contractSynchronizerId() shouldBe Some(appSynchronizerId.toProtoPrimitive),
      )

      actAndCheck(
        "Reassign the contract back to the global synchronizer",
        reassign(appSynchronizerId, globalSynchronizerId),
      )(
        "The contract is assigned to the global synchronizer again",
        _ => contractSynchronizerId() shouldBe Some(globalSynchronizerId.toProtoPrimitive),
      )
    }

  "docker-compose based localnet supports reassignment between synchronizers" in { implicit env =>
    withLocalNet() { implicit env =>
      testReassignment("app-provider", "providerValidatorClient")
      testReassignment("app-user", "userValidatorClient")
    }
  }
}

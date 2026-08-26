package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_UnpermissionValidator
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_UnpermissionValidator
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.SynchronizerPermissionState
import org.lfdecentralizedtrust.splice.util.*

import java.time.Instant
import java.util.Optional
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule

class PermissionedSynchronizerIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil
    with TokenStandardV2TestUtil
    with SynchronizerFeesTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs { case (_, c) =>
          c.copy(permissionedSynchronizer = true)
        }(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs { case (_, c) =>
          c.copy(permissionedSynchronizer = true)
        }(config)
      )
      .withManualStart

  "Onboard network in RestrictedOpen mode" in { implicit env =>
    initDso()

    clue(
      "Initially scan doesn't find ParticipantSynchronizerPermission for aliceValidatorBackend"
    ) {
      eventually() {
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          aliceValidatorBackend.participantClient.id.toProtoPrimitive,
        ) shouldBe None
      }
    }

    clue("Start Alice validator") {
      aliceValidatorBackend.start()
    }

    val trafficAmount = Math.max(
      sv1ScanBackend
        .getAmuletConfigAsOf(env.environment.clock.now)
        .decentralizedSynchronizer
        .fees
        .minTopupAmount
        .toLong,
      1_000_000L,
    )

    val sv1WalletUserParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
    sv1WalletClient.tap(10000)

    clue(s"SV1 buys MemberTraffic for ${aliceValidatorBackend.participantClient.name}") {
      createBuyTrafficRequest(
        validatorApp = sv1ValidatorBackend,
        buyer = sv1WalletUserParty,
        memberId = aliceValidatorBackend.participantClient.id.toProtoPrimitive,
        trafficAmount = trafficAmount,
        trackingId = s"traffic-for-${aliceValidatorBackend.participantClient.name}",
      )
    }

    val allValidators = Seq(
      sv2ValidatorBackend,
      sv3ValidatorBackend,
      sv4ValidatorBackend,
      aliceValidatorBackend,
    )

    clue("Verify all participants are granted ParticipantSynchronizerPermission") {
      allValidators.foreach { targetApp =>
        eventually() {
          sv1ScanBackend.getParticipantSynchronizerPermission(
            decentralizedSynchronizerId.toProtoPrimitive,
            targetApp.participantClient.id.toProtoPrimitive,
          ) shouldBe Some(
            SynchronizerPermissionState(None)
          )
        }
      }
    }

    actAndCheck(
      "Wait for Alice validator to bootstrap",
      aliceValidatorBackend.waitForInitialization(),
    )(
      "Onboard Alice test user",
      _ => {
        aliceValidatorBackend.onboardUser("TestUser")
      },
    )

    clue("Sponser SV Buys Member Traffic for Bob in the DevNet") {
      sv1Backend.devNetBuyMemberTraffic(bobValidatorBackend.participantClient.id)
    }

    clue("Verify Bob is granted ParticipantSynchronizerPermission") {
      eventually() {
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          bobValidatorBackend.participantClient.id.toProtoPrimitive,
        ) shouldBe Some(
          SynchronizerPermissionState(None)
        )
      }
    }

    clue("Bob Validator starts and onboards correctly") {
      bobValidatorBackend.startSync()
      bobValidatorBackend.onboardUser("TestUserBob")
    }

    val bobParticipantId = bobValidatorBackend.participantClient.id.toProtoPrimitive
    val suspendTime = env.environment.clock.now.plus(java.time.Duration.ofHours(1)).toInstant

    loggerFactory.suppress(
      SuppressionRule.Level(
        org.slf4j.event.Level.WARN
      )
    ) {
      clue("SVs vote to temporarily suspend Bob") {
        manuallyUnpermissionValidator(bobParticipantId, Some(suspendTime), revoked = false)
      }

      clue("Verify Bob's ParticipantSynchronizerPermission is updated with loginAfter") {
        eventually() {
          sv1ScanBackend.getParticipantSynchronizerPermission(
            decentralizedSynchronizerId.toProtoPrimitive,
            bobParticipantId,
          ) shouldBe Some(
            SynchronizerPermissionState(Some(CantonTimestamp.assertFromInstant(suspendTime)))
          )
        }
      }
      clue("SVs vote to permanently revoke Bob") {
        manuallyUnpermissionValidator(bobParticipantId, None, revoked = true)
      }

      clue("Verify Bob's ParticipantSynchronizerPermission is completely removed") {
        eventually() {
          sv1ScanBackend.getParticipantSynchronizerPermission(
            decentralizedSynchronizerId.toProtoPrimitive,
            bobParticipantId,
          ) shouldBe None
        }
      }

    }

    def manuallyUnpermissionValidator(
        participantId: String,
        loginAfter: Option[Instant],
        revoked: Boolean,
    ): Unit = {
      val action = new ARC_DsoRules(
        new SRARC_UnpermissionValidator(
          new DsoRules_UnpermissionValidator(
            participantId,
            loginAfter.map(Optional.of(_)).getOrElse(Optional.empty()),
            java.lang.Boolean.valueOf(revoked),
          )
        )
      )

      val (_, voteRequest) = actAndCheck(
        s"SV1 creates vote request to unpermission $participantId (revoked=$revoked)",
        eventuallySucceeds() {
          sv1Backend.createVoteRequest(
            sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
            action,
            "url",
            "description",
            sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
            None,
          )
        },
      )(
        "vote request has been created",
        _ => sv1Backend.listVoteRequests().filter(_.payload.action == action).head,
      )

      Seq(sv2Backend, sv3Backend, sv4Backend).foreach { sv =>
        clue(s"${sv.participantClient.name} accepts the vote request") {
          eventuallySucceeds() {
            sv.castVote(
              voteRequest.contractId,
              isAccepted = true,
              "url",
              "description",
            )
          }
        }
      }
    }

  }
}

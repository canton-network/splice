package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.SynchronizerPermissionState
import org.lfdecentralizedtrust.splice.util.*

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
  }
}

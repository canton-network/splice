package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
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
    initDsoWithSv1Only()

    val followerSvs = Seq(
      (sv2ValidatorBackend, sv2Backend, sv2ScanBackend),
      (sv3ValidatorBackend, sv3Backend, sv3ScanBackend),
      (sv4ValidatorBackend, sv4Backend, sv4ScanBackend),
    )

    for ((validator, sv, scan) <- followerSvs) {

      clue("Starting SV" + validator.participantClient.id) {
        startAllSync(sv, scan, validator)
      }

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

    val targetValidators = Seq(
      aliceValidatorBackend
    )

    val sv1WalletUserParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
    sv1WalletClient.tap(10000)

    targetValidators.foreach { targetApp =>
      clue(s"SV1 buys MemberTraffic for ${targetApp.participantClient.name}") {
        createBuyTrafficRequest(
          validatorApp = sv1ValidatorBackend,
          buyer = sv1WalletUserParty,
          memberId = targetApp.participantClient.id.toProtoPrimitive,
          trafficAmount = trafficAmount,
          trackingId = s"traffic-for-${targetApp.participantClient.name}",
        )
      }
    }

    val allValidators = Seq(
      sv2ValidatorBackend,
      sv3ValidatorBackend,
      sv4ValidatorBackend,
      aliceValidatorBackend,
    )

    clue("Verify all target validators are granted ParticipantSynchronizerPermission") {
      allValidators.foreach { targetApp =>
        eventually() {
          val permissions =
            sv1Backend.participantClientWithAdminToken.topology.participant_synchronizer_permissions
              .list(
                store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
                filterUid = targetApp.participantClient.id.filterString,
              )

          withClue(s"${targetApp.participantClient.name} should have submission permission: ") {
            permissions.map(_.item.permission) should contain(ParticipantPermission.Submission)
          }
        }
      }
    }

    actAndCheck(
      "Start Alice validator in permissioned mode",
      aliceValidatorBackend.startSync(),
    )(
      "Onboard Alice test user",
      _ => {
        aliceValidatorBackend.onboardUser("TestUser")
      },
    )

  }

}

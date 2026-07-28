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

  "Ensure Automatic ParticipantSynchronizerPermission Generation using MemberTraffic Trigger" in {
    implicit env =>
      initDso()

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
        sv2ValidatorBackend,
        sv3ValidatorBackend,
        sv4ValidatorBackend,
        aliceValidatorBackend,
      )

      sv1WalletClient.tap(10000)

      targetValidators.foreach { targetApp =>
        clue(s"SV1 buys MemberTraffic for ${targetApp.participantClient.name}") {
          buyMemberTrafficFor(
            payerApp = sv1ValidatorBackend,
            targetMember = targetApp.participantClient.id,
            trafficAmount = trafficAmount,
          )
        }
      }

      clue("Verify all target validators are granted ParticipantSynchronizer") {
        targetValidators.foreach { targetApp =>
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
  }

}

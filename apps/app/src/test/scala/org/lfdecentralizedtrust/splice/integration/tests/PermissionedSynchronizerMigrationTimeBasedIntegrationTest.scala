// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.admin.api.client.data.OnboardingRestriction.RestrictedOpen
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.util.{
  ProcessTestUtil,
  SwitchOverTimes,
  SynchronizerFeesTestUtil,
  TimeTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRulesConfig,
  DsoRules_SetConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_SetConfig
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.SynchronizerPermissionState
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment

import java.time.Duration
import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.concurrent.duration.DurationInt

class PermissionedSynchronizerMigrationTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with ProcessTestUtil
    with WalletTestUtil
    with TimeTestUtil
    with SynchronizerFeesTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .withManualStart

  "Migrate Network from UnrestrictedOpen to RestrictedOpen" in { implicit env =>
    initDso()

    clue("Initially all apps have no ParticipantSynchronizerPermission") {
      Seq(
        sv1ValidatorBackend,
        sv2ValidatorBackend,
        sv3ValidatorBackend,
        sv4ValidatorBackend,
        aliceValidatorBackend,
        bobValidatorBackend,
      ).foreach { app =>
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          app.participantClient.id.toProtoPrimitive,
        ) shouldBe None
      }
    }

    def buyMemberTraffic(validator: ValidatorAppBackendReference): Unit = {
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

      clue(s"SV1 buys MemberTraffic for ${validator.participantClient.name}") {
        createBuyTrafficRequest(
          validatorApp = sv1ValidatorBackend,
          buyer = sv1WalletUserParty,
          memberId = validator.participantClient.id.toProtoPrimitive,
          trafficAmount = trafficAmount,
          trackingId = s"traffic-for-${validator.participantClient.name}",
        )
      }
    }

    clue("Start alice and buy traffic via sv1") {
      aliceValidatorBackend.startSync()
      buyMemberTraffic(aliceValidatorBackend)
    }

    clue("Start bob") {
      bobValidatorBackend.startSync()
    }

    val permissionSwitchOverTime = CantonTimestamp.assertFromInstant(
      getLedgerTime.toInstant.plus(Duration.ofMinutes(10))
    )

    clue("Vote in a switch-over time in the future") {
      setPermissionedSynchronizerSwitchOverTime(permissionSwitchOverTime)

      eventually() {
        sv1ScanBackend
          .getDsoInfo()
          .dsoRules
          .payload
          .config
          .svOperationsSwitchOverTimes
          .toScala
          .map(_.asScala.toMap)
          .value should contain key SwitchOverTimes.PermissionedSynchronizer
      }
    }

    clue("Restart all svApps to trigger reprocessing") {
      Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).foreach { sv =>
        sv.stop()
        sv.startSync()
      }
    }

    advanceTime(Duration.ofMinutes(2))

    clue("Verify SVs and Alice have permissions granted, but not Bob") {
      Seq(
        sv1ValidatorBackend,
        sv2ValidatorBackend,
        sv3ValidatorBackend,
        sv4ValidatorBackend,
        aliceValidatorBackend,
      ).foreach { app =>
        clue(
          s"Checking PermissionSynchronizerPermission for ${app.participantClient.id.toProtoPrimitive}"
        ) {
          eventually(40.seconds) {
            val permissionAssigned = sv1ScanBackend.getParticipantSynchronizerPermission(
              decentralizedSynchronizerId.toProtoPrimitive,
              app.participantClient.id.toProtoPrimitive,
            )
            permissionAssigned shouldBe Some(SynchronizerPermissionState(None))
          }
        }
      }
      eventually(40.seconds) {
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          bobValidatorBackend.participantClient.id.toProtoPrimitive,
        ) shouldBe None
      }
    }

    clue("Fast forward time and assert RestrictedOpen is set") {
      advanceTime(Duration.ofMinutes(20))

      eventually() {
        val currentParams = sv1ValidatorBackend.participantClient.topology.synchronizer_parameters
          .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)
        currentParams.onboardingRestriction shouldBe RestrictedOpen
      }
    }

    clue("Alice onboard user succeeds") {
      aliceValidatorBackend.onboardUser("alice-user")
    }

    clue("DevNet tap bob") {
      sv1Backend.devNetBuyMemberTraffic(bobValidatorBackend.participantClient.id)
    }

    clue("Bob has ParticipantSynchronizerPermission") {
      eventually() {
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          bobValidatorBackend.participantClient.id.toProtoPrimitive,
        ) shouldBe Some(SynchronizerPermissionState(None))
      }
    }

    clue("Bob onboard user succeeds") {
      bobValidatorBackend.onboardUser("bob-user")
    }

  }

  private def setPermissionedSynchronizerSwitchOverTime(
      switchOverTime: CantonTimestamp
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val config = sv1Backend.getDsoInfo().dsoRules.payload.config
    val newConfig = new DsoRulesConfig(
      config.numUnclaimedRewardsThreshold,
      config.numMemberTrafficContractsThreshold,
      config.actionConfirmationTimeout,
      config.svOnboardingRequestTimeout,
      config.svOnboardingConfirmedTimeout,
      config.voteRequestTimeout,
      config.dsoDelegateInactiveTimeout,
      config.synchronizerNodeConfigLimits,
      config.maxTextLength,
      config.decentralizedSynchronizer,
      config.nextScheduledSynchronizerUpgrade,
      config.voteCooldownTime,
      config.nextScheduledLogicalSynchronizerUpgrade,
      Optional.of(
        (config.svOperationsSwitchOverTimes.toScala.map(_.asScala.toMap).getOrElse(Map.empty) +
          (SwitchOverTimes.PermissionedSynchronizer -> switchOverTime.toInstant)).asJava
      ),
    )
    sv1Backend.createVoteRequest(
      sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
      new ARC_DsoRules(new SRARC_SetConfig(new DsoRules_SetConfig(newConfig, Optional.empty()))),
      "url",
      "set the permissioned-synchronizer switch-over time",
      config.voteRequestTimeout,
      None,
    )

    val voteRequest = sv1Backend.listVoteRequests().head

    Seq(sv2Backend, sv3Backend, sv4Backend).foreach { sv =>
      eventuallySucceeds() {
        sv.castVote(voteRequest.contractId, isAccepted = true, "url", "description")
      }
    }
  }
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.{NonNegativeDuration, PositiveFiniteDuration}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import com.digitalasset.canton.time.PositiveFiniteDuration as InternalPositiveFiniteDuration
import com.digitalasset.canton.topology.{Member, ParticipantId}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.{
  ParticipantPermission,
  ParticipantSynchronizerPermission,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologySnapshot
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.syncoperator.automation.UnlimitedTrafficTrigger
import org.lfdecentralizedtrust.splice.syncoperator.automation.UnlimitedTrafficTrigger.UnlimitedTraffic
import org.lfdecentralizedtrust.splice.syncoperator.config.SyncOperatorTrafficControlConfig

class SyncOperatorIntegrationTest extends IntegrationTest {

  // Values the bootstrap does not set, to check that the trigger applies them to the synchronizer.
  private val trafficControlConfig = SyncOperatorTrafficControlConfig(
    readVsWriteScalingFactor = PositiveInt.tryCreate(5),
    setBalanceRequestSubmissionWindowSize = PositiveFiniteDuration.ofMinutes(4),
    baseEventCost = NonNegativeLong.tryCreate(500),
  )

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(
        Seq("simple-topology-1sv.conf", "sync-operator-topology.conf"),
        this.getClass.getSimpleName,
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSyncOperatorAppConfigs_(
          _.copy(trafficControl = trafficControlConfig)
        )(config)
      )
      .withOnlyAliceValidatorConnectingToSplitwell
      .withStandardSetup

  "sync operator" should {
    "restart cleanly" in { implicit env =>
      syncOperatorBackend.stop()
      syncOperatorBackend.startSync()
    }

    "report liveness and readiness" in { implicit env =>
      syncOperatorBackend.httpLive shouldBe true
      syncOperatorBackend.httpReady shouldBe true
    }

    "take its synchronizer id from the sequencer it is configured with" in { implicit env =>
      // Alice's participant is the one still connected to splitwell in this topology.
      val served = aliceValidatorBackend.participantClientWithAdminToken.synchronizers
        .id_of(SynchronizerAlias.tryCreate("splitwell"))
        .logical
      syncOperatorBackend.appState.synchronizerId shouldBe served
    }

    "apply the configured traffic parameters to its synchronizer" in { implicit env =>
      eventually() {
        val trafficControl = synchronizerParameters.trafficControl.value
        clue("the synchronizer has no free traffic and free confirmation responses") {
          trafficControl.maxBaseTrafficAmount shouldBe NonNegativeLong.zero
          trafficControl.freeConfirmationResponses shouldBe true
        }
        clue("the synchronizer has the configured values") {
          trafficControl.readVsWriteScalingFactor shouldBe
            trafficControlConfig.readVsWriteScalingFactor
          trafficControl.setBalanceRequestSubmissionWindowSize shouldBe
            InternalPositiveFiniteDuration.fromConfig(
              trafficControlConfig.setBalanceRequestSubmissionWindowSize
            )
          trafficControl.baseEventCost shouldBe trafficControlConfig.baseEventCost
        }
      }
    }

    "grant unlimited traffic to the mediator and to admitted participants" in { implicit env =>
      val synchronizerId = syncOperatorBackend.appState.synchronizerId
      val alice = aliceValidatorBackend.participantClientWithAdminToken
      val bob = bobValidatorBackend.participantClientWithAdminToken

      clue("the mediator is granted unlimited traffic with its next serial") {
        val mediator = sequencer
          .getMediatorSynchronizerState(synchronizerId, TopologySnapshot.Effective)
          .futureValue
          .mapping
          .active
          .forgetNE
          .loneElement
        eventually() {
          extraTrafficLimit(mediator) shouldBe Some(UnlimitedTraffic)
        }
      }

      // The bootstrap admits alice's participant, which joins splitwell during the test setup.
      clue("a participant admitted at startup is granted unlimited traffic") {
        eventually() {
          extraTrafficLimit(alice.id) shouldBe Some(UnlimitedTraffic)
        }
      }

      clue("a participant admitted before it joins has its traffic limit set") {
        val trigger = unlimitedTrafficTrigger
        trigger.pause().futureValue
        try {
          admit(bob.id)
          eventually() {
            sequencer
              .listParticipantSynchronizerPermissions(synchronizerId)
              .futureValue
              .map(_.mapping.participantId) should contain(bob.id)
          }
          trigger.runOnce().futureValue shouldBe true
          trigger.runOnce().futureValue shouldBe false
        } finally {
          trigger.resume()
        }
      }

      // Bob has not joined, so after a restart only the sequencer's SERIAL_TOO_LOW reply tells the
      // app that his limit is already set.
      clue("after a restart the app finds the limit of a participant that has not joined") {
        syncOperatorBackend.stop()
        syncOperatorBackend.startSync()
        val trigger = unlimitedTrafficTrigger
        trigger.pause().futureValue
        try {
          eventually() {
            trigger.runOnce().futureValue shouldBe false
          }
        } finally {
          trigger.resume()
        }
      }

      clue("the participant connects with unlimited traffic") {
        bob.synchronizers.connect_by_config(
          alice.synchronizers.config(SynchronizerAlias.tryCreate("splitwell")).value,
          synchronize = None,
        )
        extraTrafficLimit(bob.id) shouldBe Some(UnlimitedTraffic)
      }

      // The participant admin API in tests does not allow pings, so bob submits a topology
      // transaction instead. It only becomes effective once the sequencer accepted it.
      clue("the participant that joined late transacts") {
        bob.parties.enable(
          "late-joiner",
          synchronizer = Some(SynchronizerAlias.tryCreate("splitwell")),
          synchronizeParticipants = Seq(bob),
          synchronize = Some(NonNegativeDuration.ofSeconds(60)),
        )
      }
    }
  }

  private def sequencer(implicit env: SpliceTestConsoleEnvironment) =
    syncOperatorBackend.appState.sequencerAdminConnection

  private def unlimitedTrafficTrigger(implicit env: SpliceTestConsoleEnvironment) =
    syncOperatorBackend.appState.automation.trigger[UnlimitedTrafficTrigger]

  private def extraTrafficLimit(member: Member)(implicit
      env: SpliceTestConsoleEnvironment
  ): Option[NonNegativeLong] =
    sequencer.lookupSequencerTrafficControlState(member).futureValue.map(_.extraTrafficLimit)

  // The splitwell sequencer owns the synchronizer, so it can admit participants on its own.
  private def admit(participantId: ParticipantId)(implicit env: SpliceTestConsoleEnvironment) = {
    val synchronizerId = syncOperatorBackend.appState.synchronizerId
    sequencer
      .proposeMapping(
        TopologyStoreId.Synchronizer(synchronizerId),
        ParticipantSynchronizerPermission(
          synchronizerId,
          participantId,
          ParticipantPermission.Submission,
          None,
          None,
        ),
        PositiveInt.one,
        isProposal = false,
      )
      .futureValue
  }

  private def synchronizerParameters(implicit env: SpliceTestConsoleEnvironment) =
    syncOperatorBackend.appState.sequencerAdminConnection
      .getSynchronizerParametersState(syncOperatorBackend.appState.synchronizerId)
      .futureValue
      .mapping
      .parameters
}

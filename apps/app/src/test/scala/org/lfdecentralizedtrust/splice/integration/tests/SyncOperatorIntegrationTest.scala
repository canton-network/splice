// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.PositiveFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import com.digitalasset.canton.time.PositiveFiniteDuration as InternalPositiveFiniteDuration
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
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
  }

  private def synchronizerParameters(implicit env: SpliceTestConsoleEnvironment) =
    syncOperatorBackend.appState.sequencerAdminConnection
      .getSynchronizerParametersState(syncOperatorBackend.appState.synchronizerId)
      .futureValue
      .mapping
      .parameters
}

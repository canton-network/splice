// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.SynchronizerAlias
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest

class SyncOperatorIntegrationTest extends IntegrationTest {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(
        Seq("simple-topology-1sv.conf", "sync-operator-topology.conf"),
        this.getClass.getSimpleName,
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
  }
}

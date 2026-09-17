// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.admin.api.client.data.OnboardingRestriction.RestrictedOpen
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, WalletTestUtil}

class PermissionedSynchronizerMigrationIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withManualStart

  "Migrate Network from UnrestrictedOpen to RestrictedOpen" in { implicit env =>
    initDso()
    aliceValidatorBackend.startSync()

    val allParticipantIds = Seq(
      sv1ValidatorBackend,
      sv2ValidatorBackend,
      sv3ValidatorBackend,
      sv4ValidatorBackend,
      aliceValidatorBackend,
    ).map(_.participantClientWithAdminToken.id)

    val proposerSvs = Seq(
      sv1ValidatorBackend,
      sv2ValidatorBackend,
      sv3ValidatorBackend,
    )

    withClue("Set ParticipantSynchronizerPermission for sv1-4 and alice") {
      allParticipantIds.foreach { participantId =>
        proposerSvs.foreach { sv =>
          sv.participantClient.topology.participant_synchronizer_permissions
            .propose(
              decentralizedSynchronizerId,
              participantId,
              permission = ParticipantPermission.Submission,
            )
        }
      }
    }

    withClue("change onboarding restriction to RestrictedOpen") {
      actAndCheck(
        "Propose RestrictedOpen onboarding restriction",
        proposerSvs.foreach { sv =>
          sv.participantClient.topology.synchronizer_parameters.propose_update(
            decentralizedSynchronizerId,
            _.update(onboardingRestriction = RestrictedOpen),
          )
        },
      )(
        "Verify parameters are updated to RestrictedOpen",
        _ => {
          val currentParams = sv1ValidatorBackend.participantClient.topology.synchronizer_parameters
            .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)

          currentParams.onboardingRestriction shouldBe RestrictedOpen
        },
      )
    }

    withClue("Alice onboard user") {
      aliceValidatorBackend.onboardUser("test")
    }
  }
}

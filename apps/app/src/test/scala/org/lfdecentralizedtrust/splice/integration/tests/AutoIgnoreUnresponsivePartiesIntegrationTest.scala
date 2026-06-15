// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpiredAmuletTrigger,
  ExpiredLockedAmuletTrigger,
  UpdateExternalPartyConfigStateTrigger,
}
import org.lfdecentralizedtrust.splice.util.*
import org.slf4j.event.Level

import java.time.Duration
import scala.concurrent.duration.*

class AutoIgnoreUnresponsivePartiesIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil {

  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withTrafficTopupsDisabled
      .addConfigTransforms(
        (_, c) =>
          ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(c),
        (_, c) =>
          ConfigTransforms.updateInitialExternalPartyConfigStateTickDuration(
            NonNegativeFiniteDuration.ofMillis(500)
          )(c),
      )
      .addConfigTransforms((_, c) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
            .withPausedTrigger[UpdateExternalPartyConfigStateTrigger]
            .withPausedTrigger[ExpiredAmuletTrigger]
            .withPausedTrigger[ExpiredLockedAmuletTrigger]
        )(c)
      )
      .addConfigTransforms((_, c) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.copy(enableAutomaticRewardsCollectionAndAmuletMerging = false)
        )(c)
      )
      .addConfigTransforms((_, c) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(delegatelessAutomationExpiredAmuletBatchSize = 2)
        )(c)
      )

  "Expiry triggers auto-ignore parties whose participant is disconnected (MEDIATOR_SAYS_TX_TIMED_OUT)" in {
    implicit env =>
      val synchronizerId = decentralizedSynchronizerId

      val aliceUserId = aliceWalletClient.config.ledgerApiUser
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val sv1ParticipantId = sv1Backend.participantClientWithAdminToken.id
      val aliceParticipantId = aliceValidatorBackend.participantClient.id
      val sv1Participant = sv1Backend.participantClientWithAdminToken
      val aliceParticipant = aliceValidatorBackend.participantClient

      clue("Wait for alice's PartyToParticipant mapping to be visible on sv1") {
        eventually() {
          sv1Participant.topology.party_to_participant_mappings
            .list(synchronizerId, filterParty = aliceParty.toProtoPrimitive) should not be empty
        }
      }

      // Multi-host alice on sv1 (threshold=1) to be able to create amulets
      actAndCheck(
        "Multi-host alice on sv1Participant",
        eventuallySucceeds() {
          aliceParticipant.topology.party_to_participant_mappings.propose_delta(
            party = aliceParty,
            adds = Seq((sv1ParticipantId, ParticipantPermission.Submission)),
            store = synchronizerId,
          )
          sv1Participant.topology.party_to_participant_mappings.propose_delta(
            party = aliceParty,
            adds = Seq((sv1ParticipantId, ParticipantPermission.Submission)),
            store = synchronizerId,
          )
        },
      )(
        "alice is fully authorized on both participants",
        _ => {
          val hosts = sv1Participant.topology.party_to_participant_mappings
            .list(synchronizerId, filterParty = aliceParty.toProtoPrimitive)
            .flatMap(_.item.participants)
          hosts.exists(h => h.participantId == sv1ParticipantId && !h.onboarding) shouldBe true
          hosts.exists(h => h.participantId == aliceParticipantId && !h.onboarding) shouldBe true
        },
      )

      val numAmulets = 2
      val amuletAmount = BigDecimal(123.0)

      loggerFactory.suppress(
        SuppressionRule.forLogger[DbMultiDomainAcsStore[?]] && SuppressionRule.Level(Level.ERROR)
      ) {
        actAndCheck(
          "Create dust amulets owned by alice", {
            for (_ <- 1 to numAmulets) {
              createAmulet(
                sv1Backend.participantClientWithAdminToken,
                aliceUserId,
                aliceParty,
                amount = amuletAmount,
                holdingFee = amuletAmount,
              )
              createLockedAmulet(
                sv1Backend.participantClientWithAdminToken,
                aliceUserId,
                aliceParty,
                lockHolders = Seq(aliceParty),
                amount = amuletAmount,
                holdingFee = amuletAmount,
                expiredDuration = Duration.ofSeconds(1),
              )
            }
          },
        )(
          "Dust amulets show up in alice's wallet",
          _ => {
            aliceWalletClient.list().amulets should have length numAmulets.toLong
            aliceWalletClient.list().lockedAmulets should have length numAmulets.toLong
          },
        )
      }

      actAndCheck(
        "Remove alice from sv1 so only her own participant hosts her",
        eventuallySucceeds() {
          aliceParticipant.topology.party_to_participant_mappings.propose(
            party = aliceParty,
            newParticipants = Seq(
              (aliceParticipantId, ParticipantPermission.Submission)
            ),
            store = synchronizerId,
            mustFullyAuthorize = true,
          )
        },
      )(
        "Alice is only hosted on her own participant",
        _ => {
          val hosts = sv1Participant.topology.party_to_participant_mappings
            .list(synchronizerId, filterParty = aliceParty.toProtoPrimitive)
            .flatMap(_.item.participants)
          hosts.exists(_.participantId == sv1ParticipantId) shouldBe false
          hosts.exists(_.participantId == aliceParticipantId) shouldBe true
        },
      )

      clue("Disconnect alice's participant from the synchronizer") {
        // stop to avoid log noise.
        aliceValidatorBackend.stop()
        aliceValidatorBackend.participantClient.synchronizers.disconnect_all()
        aliceValidatorBackend.participantClient.synchronizers.is_connected(
          synchronizerId
        ) shouldBe false
      }

      actAndCheck(timeUntilSuccess = 180.seconds)(
        "Advance 4 rounds and resume expiry triggers", {
          (1 to 4).foreach(_ => advanceRoundsByOneTickViaAutomation())
          updateExternalPartyConfigStatesViaAutomation()
          updateExternalPartyConfigStatesViaAutomation()
          env.svs.local.foreach { sv =>
            sv.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTrigger].resume()
            sv.dsoDelegateBasedAutomation.trigger[ExpiredLockedAmuletTrigger].resume()
          }
        },
      )(
        "Alice is added to the ignored parties store after mediator timeout",
        _ => {
          sv1Backend.dsoDelegateBasedAutomation.expiredAmuletIgnoredPartiesStore.getAll should contain(
            aliceParty
          )
        },
      )

      // reconnect or other tests might get unhappy, in particular `withNoVettedPackages` gets confused if the nodes is disconnected.
      clue("Reconnect alice's participant") {
        aliceValidatorBackend.participantClient.synchronizers.reconnect_all()
      }
  }
}

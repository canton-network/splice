// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.{DarResource, DarResources}
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

import scala.concurrent.duration.*
import java.time.Duration

/** Same scenario as `AmuletExpiryIntegrationTest`, but the dust amulets are owned by `alice`,
  * whose validator (`aliceValidator`) is stuck at the minimal initialize amulet — even though the DSO
  * (running on sv1) has been upgraded to 0.1.17. The expiry triggers must therefore fall back
  * to the V1 choices.
  */
class AmuletWithMinimalVersionExpiryIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil
    with PackageUnvettingUtil {

  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  protected def supportedPackagesToUnvet(
      packages: Seq[DarResource]
  ): Map[PackageName, Set[PackageVersion]] =
    packages
      .groupBy(_.metadata.name)
      .map { case (name, resources) => name -> resources.map(_.metadata.version).toSet }

  // have alice vet only the minimal required package versions
  private val unvetOnAlice = supportedPackagesToUnvet(
    DarResourcesUtil.supportedPackageVersions
      .filterNot(DarResourcesUtil.minimalPackageVersions.contains(_))
  )

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withNoVettedPackages(implicit env => Seq(aliceValidatorBackend.participantClient))
      .withTrafficTopupsDisabled
      .addConfigTransforms(
        (_, c) =>
          ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(c),
        (_, c) =>
          ConfigTransforms.updateInitialExternalPartyConfigStateTickDuration(
            NonNegativeFiniteDuration.ofMillis(500)
          )(c),
      )
      .addConfigTransforms((_, config) => {
        val aliceVal = InstanceName.tryCreate("aliceValidator")
        config.copy(
          validatorApps = config.validatorApps +
            (aliceVal -> config
              .validatorApps(aliceVal)
              .copy(additionalPackagesToUnvet = unvetOnAlice))
        )
      })
      .addConfigTransforms((_, c) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
            .withPausedTrigger[UpdateExternalPartyConfigStateTrigger]
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

  "Amulet expiry works for amulets owned by alice (stuck at 0.1.16) after sv1 is on 0.1.17" in {
    implicit env =>
      val synchronizerId = decentralizedSynchronizerId

      clue("aliceValidator doesn't vet amulet 0.1.17 and 0.1.18 packages") {
        eventually() {
          val vetted = getVettedPackageIds(
            aliceValidatorBackend.appState.participantAdminConnection,
            synchronizerId,
          ).toSet
          vetted should not contain DarResources.amulet_0_1_17.packageId
          vetted should not contain DarResources.amulet_0_1_18.packageId
        }
      }

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

      // Multi-host alice on sv1Participant to be able to create bare Amulet and LockedAmulet contracts
      actAndCheck(
        "Multi-host alice on sv1Participant (alice keeps her old host)",
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
          "Create V1-pinned dust amulets owned by alice", {
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

      actAndCheck(timeUntilSuccess = 60.seconds)(
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
        "Dust amulets are expired via V1 choices",
        _ => {
          aliceWalletClient.list().amulets shouldBe empty withClue "dust amulets"
          aliceWalletClient.list().lockedAmulets shouldBe empty withClue "dust lockedAmulets"
        },
      )
  }
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  AppRewardCoupon,
  FeaturedAppActivityMarker,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.{DarResource, DarResources}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireRewardCouponsTrigger,
  ExpiredAmuletTrigger,
  ExpiredLockedAmuletTrigger,
  FeaturedAppActivityMarkerTrigger,
  UpdateExternalPartyConfigStateTrigger,
}
import org.lfdecentralizedtrust.splice.util.*
import org.slf4j.event.Level

import scala.concurrent.duration.*
import java.time.Duration

abstract class AmuletExpiryWithOldPackageIntegrationTestBase
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil
    with PackageUnvettingUtil {

  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  protected val ignoredAmuletVersions: Set[String] = Set.empty

  protected def packagesToUnvetOnAlice(
      packages: Seq[DarResource]
  ): Map[PackageName, Set[PackageVersion]] =
    packages
      .groupBy(_.metadata.name)
      .map { case (name, resources) => name -> resources.map(_.metadata.version).toSet }

  // have alice vet only the minimal required package versions
  private val unvetOnAlice = packagesToUnvetOnAlice(
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
            .withPausedTrigger[ExpireRewardCouponsTrigger]
            .withPausedTrigger[FeaturedAppActivityMarkerTrigger]
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
      .addConfigTransforms((_, c) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(ignoredAmuletVersions = ignoredAmuletVersions)
        )(c)
      )

  def setupAliceWithDustAmulets()(implicit env: SpliceTestConsoleEnvironment): PartyId = {
    val synchronizerId = decentralizedSynchronizerId

    clue("aliceValidator has not vetted splice-amulet 0.1.17 and 0.1.18") {
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
      aliceParty
    }
  }
}

/** Tests that expiry triggers fall back to V1 choices when alice's validator
  * has only vetted minimal package versions (not splice-amulet 0.1.17+).
  */
class AmuletExpiryWithMinimalPackageIntegrationTest
    extends AmuletExpiryWithOldPackageIntegrationTestBase {

  "Amulet expiry falls back to V1 choices when alice's validator has not vetted splice-amulet 0.1.17" in {
    implicit env =>
      setupAliceWithDustAmulets()
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

/** Tests that expiry triggers skip batches when the task's amulet preferred package version
  * is listed in `ignoredAmuletVersions`, adding the party to the ignored-parties store.
  */
class AmuletBasedExpiryWithIgnoredPackageIntegrationTest
    extends AmuletExpiryWithOldPackageIntegrationTestBase {

  override val ignoredAmuletVersions: Set[String] = Set(
    DarResources.amulet_0_1_15.metadata.version.toString
  )

  "Triggers expiring amulet, locked amulet, and reward coupons and featured app markers skip parties when their preferred amulet package version is marked as ignored" in {
    implicit env =>
      val aliceParty: PartyId = setupAliceWithDustAmulets()
      advanceRoundsByOneTickViaAutomation()
      advanceRoundsByOneTickViaAutomation()

      val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
      val currentRound = openRounds.toList.headOption.value.payload.round

      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(dsoParty),
          readAs = Seq.empty,
          update = new AppRewardCoupon(
            dsoParty.toProtoPrimitive,
            aliceParty.toProtoPrimitive,
            false,
            BigDecimal(10.0).bigDecimal,
            currentRound,
            java.util.Optional.empty(),
          ).create,
        )

      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(dsoParty),
          readAs = Seq.empty,
          update = new FeaturedAppActivityMarker(
            dsoParty.toProtoPrimitive,
            aliceParty.toProtoPrimitive,
            aliceParty.toProtoPrimitive,
            BigDecimal(1.0).bigDecimal,
          ).create,
        )

      actAndCheck(timeUntilSuccess = 60.seconds)(
        "Advance 4 rounds and resume expiry triggers", {
          (1 to 4).foreach(_ => advanceRoundsByOneTickViaAutomation())
          updateExternalPartyConfigStatesViaAutomation()
          updateExternalPartyConfigStatesViaAutomation()
          env.svs.local.foreach { sv =>
            sv.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTrigger].resume()
            sv.dsoDelegateBasedAutomation.trigger[ExpiredLockedAmuletTrigger].resume()
            sv.dsoDelegateBasedAutomation.trigger[ExpireRewardCouponsTrigger].resume()
            sv.dsoDelegateBasedAutomation.trigger[FeaturedAppActivityMarkerTrigger].resume()
          }
        },
      )(
        "Dust contracts remain because preferred version 0.1.15 is in ignoredAmuletVersions",
        _ => {
          sv1Backend.dsoDelegateBasedAutomation.expiredAmuletIgnoredPartiesStore.getAll should contain(
            aliceParty
          )
          aliceWalletClient.list().amulets should have length 2L withClue "amulets should remain"
          aliceWalletClient
            .list()
            .lockedAmulets should have length 2L withClue "locked amulets should remain"
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(AppRewardCoupon.COMPANION)(
              dsoParty,
              co => co.data.provider == aliceParty.toProtoPrimitive,
            ) should have size 1L withClue "app reward coupon should remain"
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(FeaturedAppActivityMarker.COMPANION)(
              dsoParty,
              co => co.data.provider == aliceParty.toProtoPrimitive,
            ) should have size 1L withClue "featured app activity marker should remain"
        },
      )
  }
}

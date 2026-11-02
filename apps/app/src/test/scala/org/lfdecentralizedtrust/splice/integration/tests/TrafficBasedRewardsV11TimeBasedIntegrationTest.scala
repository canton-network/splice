package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.event.Event
import com.daml.ledger.api.v2.transaction_filter
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands.UpdateService.TransactionWrapper
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import java.time.Duration
import java.util.{Optional, UUID}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppKind
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  RewardConfig,
  USD,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_GrantFeaturedAppRight,
  DsoRules_RevokeFeaturedAppRight,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_GrantFeaturedAppRight,
  SRARC_RevokeFeaturedAppRight,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import org.lfdecentralizedtrust.splice.sv.config.InitialRewardConfig
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.GetRewardAccountingActivityTotalsResponse
import definitions.GetRewardAccountingBatchResponse
import definitions.GetRewardAccountingRootHashResponse
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.{
  CalculateRewardsTrigger,
  CalculateRewardsDryRunTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  AmuletConfigUtil,
  ScanTestUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Random

// Covers TBAR v1.1 asset-side activity (CIP-104 amendment).
// Pre-amendment behaviour continues to be covered by
// TrafficBasedRewardsTimeBasedIntegrationTest.
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25
abstract class TrafficBasedRewardsV11TimeBasedIntegrationTestBase
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with TimeTestUtil
    with AmuletConfigUtil
    with ExternallySignedPartyTestUtil
    with ScanTestUtil
    with TokenStandardTest {

  protected def rewardConfigMode
      : TrafficBasedRewardsV11TimeBasedIntegrationTestBase.RewardConfigMode

  private def dryRunEnabled: Boolean =
    rewardConfigMode == TrafficBasedRewardsV11TimeBasedIntegrationTestBase.RewardConfigMode.DryRun

  private def mintingTrafficBased: Boolean =
    rewardConfigMode == TrafficBasedRewardsV11TimeBasedIntegrationTestBase.RewardConfigMode.MintingTrafficBased

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        Seq(
          sv1ValidatorBackend,
          aliceValidatorBackend,
          bobValidatorBackend,
          splitwellValidatorBackend,
        ).foreach { backend =>
          backend.participantClient.upload_dar_unless_exists(tokenStandardTestDarPath)
        }
      })
      .addConfigTransform((_, config) =>
        ConfigTransforms.withRewardConfig(
          InitialRewardConfig(
            mintingVersion =
              if (mintingTrafficBased)
                TrafficBasedRewardsV11TimeBasedIntegrationTestBase.trafficBasedAppRewards
              else TrafficBasedRewardsV11TimeBasedIntegrationTestBase.featuredAppMarkers,
            dryRunVersion = Option.when(dryRunEnabled)(
              TrafficBasedRewardsV11TimeBasedIntegrationTestBase.trafficBasedAppRewards
            ),
            appRewardCouponThreshold =
              TrafficBasedRewardsV11TimeBasedIntegrationTestBase.appRewardCouponThreshold,
          )
        )(config)
      )
      // Pause background wallet/validator automation so round-level assertions
      // are not interleaved with background minting.
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
            .withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        )(config)
      )
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
        )(config)
      )

  "TBAR v1.1 asset-side activity pipeline works" in { implicit env =>
    val assetMoverParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val assetParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

    clue(s"Grant FAK_AssetMoving right to $assetMoverParty") {
      grantFeaturedAppRightWithKind(
        assetMoverParty,
        FeaturedAppKind.FAK_ASSETMOVING,
        opsParties = Seq(aliceValidatorBackend.getValidatorPartyId()),
      )
    }
    clue(s"Grant FAK_Asset right to $assetParty") {
      grantFeaturedAppRightWithKind(
        assetParty,
        FeaturedAppKind.FAK_ASSET,
        opsParties = Seq(bobValidatorBackend.getValidatorPartyId()),
      )
    }
    val highWeightAssetParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
    clue(s"Grant FAK_Asset right (weight=3) to $highWeightAssetParty") {
      grantFeaturedAppRightWithKind(
        highWeightAssetParty,
        FeaturedAppKind.FAK_ASSET,
        opsParties = Seq(aliceValidatorBackend.getValidatorPartyId()),
        weight = BigDecimal(3),
      )
    }
    val doomedAssetParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
    clue(s"Grant FAK_Asset right (weight=1) to $doomedAssetParty (will be revoked mid-flow)") {
      grantReceiverOnlyFeaturedAppRight(doomedAssetParty, FeaturedAppKind.FAK_ASSET)
    }
    // Fresh ledger-api user + createIfMissing=true: onboardUser without
    // createIfMissing only binds a user to the hinted PartyId without allocating
    // it, which breaks subsequent submissions with UNKNOWN_SUBMITTERS. Reusing
    // splitwellWalletClient's user would also silently return the existing
    // primary party (splitwell__provider), colliding with the non-FA sender.
    val venuePartyHint = s"venue-party-${Random.nextInt().abs}"
    val venueParty = splitwellValidatorBackend.onboardUser(
      s"venue-user-${Random.nextInt().abs}",
      Some(
        PartyId.tryFromProtoPrimitive(
          s"$venuePartyHint::${splitwellValidatorBackend.participantClient.id.namespace.toProtoPrimitive}"
        )
      ),
      createIfMissing = Some(true),
    )
    clue(s"Grant FAK_AssetMoving right to $venueParty for DvP settlements") {
      grantFeaturedAppRightWithKind(
        venueParty,
        FeaturedAppKind.FAK_ASSETMOVING,
        opsParties = Seq(splitwellValidatorBackend.getValidatorPartyId()),
      )
    }
    updateTbarV11RewardConfig()

    bobWalletClient.createTransferPreapproval()
    charlieWalletClient.createTransferPreapproval()
    sv1WalletClient.createTransferPreapproval()
    aliceWalletClient.tap(1000.0)
    bobWalletClient.tap(1000.0)
    charlieWalletClient.tap(1000.0)
    sv1WalletClient.tap(1000.0)

    // splitwell's wallet user has no FA grant — the baseline round's non-FA
    // negative-case needs a submitter that misses both findFASubmitter paths.
    val nonFaSenderParty = onboardWalletUser(splitwellWalletClient, splitwellValidatorBackend)
    splitwellWalletClient.tap(1000.0)

    assertOldestOpenRound(0)

    clue(
      "Reward accounting: earliest available round is 0 after bootstrap but totals are not yet computed"
    ) {
      eventually() {
        sv1ScanBackend.getRewardAccountingEarliestAvailableRound() shouldBe Some(0L)
      }
      sv1ScanBackend.getRewardAccountingActivityTotals(0L) shouldBe an[
        GetRewardAccountingActivityTotalsResponse.members.RewardAccountingActivityTotalsUndetermined
      ]
    }

    val baselineRound = 5L
    val postVoteRound = 10L

    // Pause CalculateRewardsTrigger and its dry-run twin so each round's
    // CalculateRewardsV2 contracts are observable before consumption, matching
    // the pre-amendment sibling test.
    val calculateRewardsTriggers =
      activeSvs.map(_.dsoAutomation.trigger[CalculateRewardsTrigger])
    val calculateRewardsDryRunTriggers =
      activeSvs.map(_.dsoAutomation.trigger[CalculateRewardsDryRunTrigger])

    def advanceRoundsOpening(range: Range): Unit =
      range.foreach { round =>
        advanceTimeAndWaitForRoundOpening
        assertOldestOpenRound(round.toLong)
      }

    // Replayed in rounds 10, 15, 20. Per-party weights (and thus per-party
    // rewards) are stable across replays, which phase 2a relies on for its
    // calibration to transfer to phase 2b.
    def runFourPartyScenario(): (String, String, String, String) = {
      val aliceBob = settleTrade(
        aliceWalletClient,
        assetMoverParty,
        bobWalletClient,
        assetParty,
        venueParty,
        walletUsdToAmulet(100.0),
        walletUsdToAmulet(20.0),
      )
      val aliceCharlie = settleTrade(
        aliceWalletClient,
        assetMoverParty,
        charlieWalletClient,
        highWeightAssetParty,
        venueParty,
        walletUsdToAmulet(100.0),
        walletUsdToAmulet(20.0),
        aliceValidatorBackend,
      )
      val aliceSv1 = settleTrade(
        aliceWalletClient,
        assetMoverParty,
        sv1WalletClient,
        doomedAssetParty,
        venueParty,
        walletUsdToAmulet(100.0),
        walletUsdToAmulet(20.0),
        sv1ValidatorBackend,
      )
      val bobCharlie = transferPreapprovalSendCapturingUpdateId(
        senderWallet = bobWalletClient,
        senderParty = assetParty,
        senderParticipant = bobValidatorBackend.participantClientWithAdminToken,
        receiver = highWeightAssetParty,
        amount = BigDecimal(5),
      )
      (aliceBob, aliceCharlie, aliceSv1, bobCharlie)
    }

    val (uid5Dvp1, uid5Direct, uid5Dvp2, uid10Dvp1, uid10Dvp2, uid10Dvp3, uid10Direct) =
      setTriggersWithin(triggersToPauseAtStart =
        calculateRewardsTriggers ++ calculateRewardsDryRunTriggers
      ) {
        for (round <- 1 to 5) {
          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(round.toLong)
        }
        pauseScanVerdictIngestionWithin(sv1ScanBackend) {

          // Round 5 scenario: cap=0.1. Baseline FA ratios + non-FA negative case.
          val id5Dvp1 = settleTrade(
            aliceWalletClient,
            assetMoverParty,
            bobWalletClient,
            assetParty,
            venueParty,
            walletUsdToAmulet(100.0),
            walletUsdToAmulet(20.0),
          )
          val id5Direct = transferPreapprovalSendCapturingUpdateId(
            senderWallet = splitwellWalletClient,
            senderParty = nonFaSenderParty,
            senderParticipant = splitwellValidatorBackend.participantClientWithAdminToken,
            receiver = assetParty,
            amount = BigDecimal(5),
          )
          val id5Dvp2 = settleTrade(
            aliceWalletClient,
            assetMoverParty,
            sv1WalletClient,
            doomedAssetParty,
            venueParty,
            walletUsdToAmulet(100.0),
            walletUsdToAmulet(20.0),
            sv1ValidatorBackend,
          )
          advanceRoundsOpening(6 to 7)

          clue("Lower per-asset cap, lower threshold, and revoke doomedAssetParty in one window") {
            updateTbarV11RewardConfig(
              maxExtraFeaturedAssetActivityWeightPerAsset = BigDecimal("0.05"),
              appRewardCouponThreshold = BigDecimal("5"),
            )
            revokeFeaturedAppRight(doomedAssetParty)
          }
          advanceRoundsOpening(8 to 10)

          // Round 10 scenario: cap=0.05. Cap clamp + weight scaling + post-revocation + FAK_ASSET-submitter cases.
          val (id10Dvp1, id10Dvp2, id10Dvp3, id10Direct) = runFourPartyScenario()
          advanceRoundsOpening(11 to 12)

          clue("CalculateRewardsV2 contracts should exist for each scenario round") {
            eventually() {
              val calculateRewardsRounds =
                sv1Backend.appState.dsoStore
                  .listCalculateRewardsV2()
                  .futureValue
                  .map(_.payload.round.number)
                  .toSet
              (0L to 10L).foreach { round =>
                calculateRewardsRounds should contain(round) withClue
                  s"CalculateRewardsV2 should exist for round $round"
              }
            }
          }

          (id5Dvp1, id5Direct, id5Dvp2, id10Dvp1, id10Dvp2, id10Dvp3, id10Direct)
        }
      }

    clue(
      s"Round $baselineRound DvP event (alice↔$assetParty): per-record asset weight is ~10% of burn (cap=0.1, weight=1)"
    ) {
      assertAssetRatioInEvent(
        event = fetchEvent(uid5Dvp1, s"baseline DvP $uid5Dvp1"),
        submitter = venueParty,
        assetParty = assetParty,
        expectedPct = 10,
        tolerancePct = 5,
        label = s"baseline DvP alice↔$assetParty (venue submits)",
      )
    }

    clue(
      s"Round $baselineRound DvP event (alice↔$doomedAssetParty): same baseline — doomed party still active"
    ) {
      assertAssetRatioInEvent(
        event = fetchEvent(uid5Dvp2, s"baseline DvP $uid5Dvp2"),
        submitter = venueParty,
        assetParty = doomedAssetParty,
        expectedPct = 10,
        tolerancePct = 5,
        label = s"baseline DvP alice↔$doomedAssetParty (pre-revocation)",
      )
    }

    clue(
      s"Round $baselineRound non-FA event (splitwell→$assetParty): app records only, no asset activity, no submitting_app_provider"
    ) {
      val noFaEvent = fetchEvent(uid5Direct, s"non-FA direct $uid5Direct")
      noFaEvent.submittingAppProvider shouldBe None withClue
        s"Non-FA-submitted event $uid5Direct should have no submitting_app_provider"
      noFaEvent.appActivityRecords.exists(_.records.nonEmpty) shouldBe true withClue
        s"Non-FA-submitted event $uid5Direct should have app records"
      noFaEvent.assetActivityRecords shouldBe None withClue
        s"Non-FA-submitted event $uid5Direct should have no asset activity records"
    }

    clue(
      s"Round $baselineRound DvP event (alice↔$assetParty): getEventById reports submitting_app_provider = $venueParty"
    ) {
      val reloaded = fetchEvent(uid5Dvp1, s"baseline DvP $uid5Dvp1 submitting_app_provider")
      reloaded.submittingAppProvider.map(_.value) shouldBe Some(
        venueParty.toProtoPrimitive
      ) withClue
        s"DvP settlement $uid5Dvp1 should report $venueParty as submitting_app_provider"
    }

    clue(
      s"Round $postVoteRound cap-crossed DvP (alice↔$assetParty, w=1, cap=0.05): per-record asset ratio ~5%"
    ) {
      assertAssetRatioInEvent(
        event = fetchEvent(uid10Dvp1, s"cap-crossed DvP $uid10Dvp1"),
        submitter = venueParty,
        assetParty = assetParty,
        expectedPct = 5,
        tolerancePct = 4,
        label = s"cap-crossed DvP alice↔$assetParty",
      )
    }

    clue(
      s"Round $postVoteRound weight-tripled DvP (alice↔$highWeightAssetParty, w=3, cap=0.05): per-record asset ratio ~15%"
    ) {
      assertAssetRatioInEvent(
        event = fetchEvent(uid10Dvp2, s"weight-tripled DvP $uid10Dvp2"),
        submitter = venueParty,
        assetParty = highWeightAssetParty,
        expectedPct = 15,
        tolerancePct = 6,
        label = s"weight-tripled DvP alice↔$highWeightAssetParty",
      )
    }

    clue(
      s"Round $postVoteRound post-revocation DvP (alice↔$doomedAssetParty): no asset record for the revoked party; only DSO-share re-attributed to $venueParty"
    ) {
      val event = fetchEvent(uid10Dvp3, s"post-revocation DvP $uid10Dvp3")
      val assetPartyProtos = event.assetActivityRecords
        .getOrElse(fail(s"post-revocation DvP $uid10Dvp3 should carry assetActivityRecords"))
        .value
        .map(_.party)
        .toSet
      assetPartyProtos should not contain doomedAssetParty.toProtoPrimitive withClue
        s"$doomedAssetParty was revoked; its FA grant should no longer attribute asset activity"
      assetPartyProtos shouldBe Set(venueParty.toProtoPrimitive) withClue
        s"Only DSO's share should remain, re-attributed to the submitting app $venueParty"
    }

    clue(
      s"Round $postVoteRound FAK_ASSET-submitter event (bob→$highWeightAssetParty): bob gets full burn as app activity; no asset-side"
    ) {
      val event = fetchEvent(uid10Direct, s"FAK_ASSET submitter direct $uid10Direct")
      val submitterProto = assetParty.toProtoPrimitive
      event.submittingAppProvider.map(_.value) shouldBe Some(submitterProto) withClue
        s"FAK_ASSET-submitted event $uid10Direct should report $assetParty as submitting_app_provider"
      val appRecords = event.appActivityRecords
        .getOrElse(fail("FAK_ASSET-submitted event should carry appActivityRecords"))
        .records
      appRecords.map(_.party).toSet shouldBe Set(submitterProto) withClue
        s"Submitter $assetParty should be the only app provider for the FAK_ASSET-submitted event"
      event.assetActivityRecords shouldBe None withClue
        "computeAssetActivity does not fire for FAK_ASSET submitters, so no asset-side records are produced"
    }

    clue(
      s"Round $postVoteRound IssuingMiningRound.optIssuancePerFeaturedAssetRewardCoupon is unpopulated"
    ) {
      // Pin: field stays None until round-close summary populates it from
      // asset-activity totals; this assertion will then fire and should be
      // tightened to the CIP-104 formula. Must run before phase 2a — phase 2a's
      // extra rounds advance past ExpireIssuingMiningRoundTrigger for round 10.
      val issuingRound = eventually() {
        sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._2
          .find(_.contract.payload.round.number == postVoteRound)
          .getOrElse(
            fail(s"IssuingMiningRound for round $postVoteRound not found in scan")
          )
      }
      issuingRound.contract.payload.optIssuancePerFeaturedAssetRewardCoupon.toScala shouldBe None withClue
        s"optIssuancePerFeaturedAssetRewardCoupon has a value — tighten this test to the CIP-104 formula"
    }

    // Phase 2a: near-zero threshold replay of the round-10 scenario, so the
    // batch tree surfaces every party's amount. Per-party amounts only depend
    // on per-party activity weights (not the threshold), so these amounts
    // predict phase 2b under the calibrated threshold.
    val measurementRound = 15L

    val (uid15Dvp1, uid15Dvp2, uid15Dvp3, uid15Direct) =
      setTriggersWithin(triggersToPauseAtStart =
        calculateRewardsTriggers ++ calculateRewardsDryRunTriggers
      ) {
        pauseScanVerdictIngestionWithin(sv1ScanBackend) {
          clue(
            s"Phase 2a: near-zero threshold so round $measurementRound measurement sees every party"
          ) {
            updateTbarV11RewardConfig(
              maxExtraFeaturedAssetActivityWeightPerAsset = BigDecimal("0.05"),
              appRewardCouponThreshold = BigDecimal("0.001"),
            )
          }
          advanceRoundsOpening(13 to 15)

          val scenario15 = runFourPartyScenario()
          advanceRoundsOpening(16 to 17)
          scenario15
        }
      }
    val _ = (uid15Dvp1, uid15Dvp2, uid15Dvp3, uid15Direct)

    val round15Amounts = eventually() {
      getMintingAllowancesForRound(measurementRound)
        .map(a => BigDecimal(a.amount))
        .sorted
    }
    val calibratedThreshold = round15Amounts match {
      case smallest +: second +: _ => (smallest + second) / 2
      case _ =>
        fail(
          s"Round $measurementRound minting allowances need at least 2 parties, got $round15Amounts"
        )
    }
    // appRewardCouponThreshold is in USD; scan converts via
    // `threshold_CC = threshold_USD / amuletPrice`. Convert the measured CC
    // midpoint back to USD before voting.
    val amuletPriceUsdPerCc =
      sv1ScanBackend
        .getLatestOpenMiningRound(env.environment.clock.now)
        .contract
        .payload
        .amuletPrice
    val calibratedThresholdUsd = calibratedThreshold * BigDecimal(amuletPriceUsdPerCc)
    logger.info(
      s"Phase 2a measurement: round $measurementRound per-party amounts (ascending CC) = " +
        round15Amounts.map(_.toString).mkString(", ") +
        s"; calibrated threshold = $calibratedThreshold CC = $calibratedThresholdUsd USD" +
        s" (amuletPrice = $amuletPriceUsdPerCc USD/CC)"
    )

    // Phase 2b: replay under the calibrated threshold — smallest party falls
    // below, others clear, exercising the mixed above/below branch.
    val verificationRound = 20L

    val (uid20Dvp1, uid20Dvp2, uid20Dvp3, uid20Direct) =
      setTriggersWithin(triggersToPauseAtStart =
        calculateRewardsTriggers ++ calculateRewardsDryRunTriggers
      ) {
        pauseScanVerdictIngestionWithin(sv1ScanBackend) {
          clue(
            s"Phase 2b: vote calibrated threshold $calibratedThresholdUsd USD (= $calibratedThreshold CC) for round $verificationRound"
          ) {
            updateTbarV11RewardConfig(
              maxExtraFeaturedAssetActivityWeightPerAsset = BigDecimal("0.05"),
              appRewardCouponThreshold = calibratedThresholdUsd,
            )
          }
          advanceRoundsOpening(18 to 20)

          val scenario20 = runFourPartyScenario()
          advanceRoundsOpening(21 to 22)
          scenario20
        }
      }
    val _ = (uid20Dvp1, uid20Dvp2, uid20Dvp3, uid20Direct)

    clue(
      s"Round $verificationRound: calibrated threshold $calibratedThresholdUsd USD (= $calibratedThreshold CC) splits parties above/below"
    ) {
      assertRoundTotalsOk(verificationRound) { t =>
        BigDecimal(t.totalAppRewardMintingAllowance) should be > BigDecimal(0) withClue
          s"Round $verificationRound: at least one party should clear calibrated threshold $calibratedThresholdUsd USD"
        BigDecimal(t.totalAppRewardThresholded) should be > BigDecimal(0) withClue
          s"Round $verificationRound: at least one party should fall below calibrated threshold $calibratedThresholdUsd USD"
      }
    }
  }

  // Votes an AmuletConfig update that sets the three TBAR v1.1 RewardConfig
  // Optional fields (defaulted to CIP-104 values) plus the threshold.
  // InitialRewardConfig leaves these None at bootstrap, so computeForFASubmitted
  // short-circuits until this runs.
  private def updateTbarV11RewardConfig(
      extraFeaturedAssetActivityWeight: BigDecimal = BigDecimal("0.2"),
      maxExtraFeaturedAssetActivityWeightPerAsset: BigDecimal = BigDecimal("0.1"),
      dsoAppActivityWeight: BigDecimal = BigDecimal("0.1"),
      appRewardCouponThreshold: BigDecimal =
        TrafficBasedRewardsV11TimeBasedIntegrationTestBase.appRewardCouponThreshold,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val amuletRules = sv1Backend.getDsoInfo().amuletRules
    val existing = AmuletConfigSchedule(amuletRules).getConfigAsOf(env.environment.clock.now)
    val rc = existing.rewardConfig.get()
    val newRc = new RewardConfig(
      rc.mintingVersion,
      rc.dryRunVersion,
      rc.batchSize,
      rc.rewardCouponTimeToLive,
      appRewardCouponThreshold.bigDecimal,
      Optional.of(extraFeaturedAssetActivityWeight.bigDecimal),
      Optional.of(maxExtraFeaturedAssetActivityWeightPerAsset.bigDecimal),
      Optional.of(dsoAppActivityWeight.bigDecimal),
    )
    val newConfig = new AmuletConfig[USD](
      existing.transferConfig,
      existing.issuanceCurve,
      existing.decentralizedSynchronizer,
      existing.tickDuration,
      existing.packageConfig,
      existing.transferPreapprovalFee,
      existing.featuredAppActivityMarkerAmount,
      existing.optDevelopmentFundManager,
      existing.externalPartyConfigStateTickDuration,
      Optional.of(newRc),
      existing.transferPreapprovalBaseDuration,
      existing.developmentFundManagerBlacklist,
      existing.minDevelopmentFundMintingDelay,
      existing.amuletSwitchOverTimes,
      existing.governanceLockConfig,
    )
    setAmuletConfig(Seq((None, newConfig, existing)))
    eventually() {
      sv1Backend.listVoteRequests() shouldBe empty
    }
  }

  // Grants an FA right via DsoRules_GrantFeaturedAppRight and blocks until scan
  // observes it. opsParties lists validator-operator parties that submit on
  // behalf of the provider; findFASubmitter uses that mapping to attribute
  // wallet-submitted transactions back to the user's FA grant.
  private def grantFeaturedAppRightWithKind(
      party: PartyId,
      kind: FeaturedAppKind,
      opsParties: Seq[PartyId],
      weight: BigDecimal = BigDecimal(1),
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val opsPartiesArg =
      if (opsParties.isEmpty) java.util.Optional.empty[java.util.List[String]]()
      else
        java.util.Optional.of(
          java.util.List.copyOf(opsParties.map(_.toProtoPrimitive).asJava)
        )
    val action = new ARC_DsoRules(
      new SRARC_GrantFeaturedAppRight(
        new DsoRules_GrantFeaturedAppRight(
          party.toProtoPrimitive,
          java.util.Optional.of(weight.bigDecimal),
          java.util.Optional.of(kind),
          opsPartiesArg,
        )
      )
    )
    votingFlow(action, effectivity = None, accept = true, expiration = Duration.ofSeconds(60))
    clue(s"Wait for $party FA right to be ingested with kind=$kind weight=$weight") {
      eventually() {
        val right = sv1ScanBackend.lookupFeaturedAppRight(party).value
        right.payload.appKind.toScala shouldBe Some(kind)
        right.payload.activityWeight.toScala
          .map(BigDecimal(_))
          .getOrElse(BigDecimal(1)) shouldBe weight
      }
    }
  }

  // Grants an FA right for a receiver/confirmer-only party. Passes empty
  // opsParties — needed because Amulet.daml's `validFeaturedAppRight` requires
  // `provider notElem opsParties`, which fails when provider == its own
  // validator-operator (e.g. an SV wallet in a 1-SV topology).
  private def grantReceiverOnlyFeaturedAppRight(
      party: PartyId,
      kind: FeaturedAppKind,
      weight: BigDecimal = BigDecimal(1),
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    grantFeaturedAppRightWithKind(party, kind, opsParties = Seq.empty, weight = weight)

  // Votes DsoRules_RevokeFeaturedAppRight and blocks until scan no longer
  // observes the right. From this point lookupFeaturedAppPartiesAsOf treats
  // `party` as non-featured for subsequent rounds.
  private def revokeFeaturedAppRight(
      party: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val rightCid = clue(s"Look up featured app right for $party") {
      sv1ScanBackend.lookupFeaturedAppRight(party).value.contractId
    }
    val action = new ARC_DsoRules(
      new SRARC_RevokeFeaturedAppRight(
        new DsoRules_RevokeFeaturedAppRight(rightCid)
      )
    )
    votingFlow(action, effectivity = None, accept = true, expiration = Duration.ofSeconds(60))
    clue(s"Wait for $party FA right to be withdrawn from scan") {
      eventually() {
        sv1ScanBackend.lookupFeaturedAppRight(party) shouldBe None
      }
    }
  }

  private def assertOldestOpenRound(
      expectedOldestRound: Long
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue(s"Asserting oldest open round=$expectedOldestRound") {
      eventually() {
        sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .map(_.payload.round.number)
          .min shouldBe expectedOldestRound
      }
    }
  }

  // Waits for Ok totals, then runs `check` against them. Fails if scan keeps
  // responding Undetermined/CannotProvide past the timeout.
  private def assertRoundTotalsOk(round: Long, timeout: FiniteDuration = 30.seconds)(
      check: definitions.RewardAccountingActivityTotalsOk => Unit
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    eventually(timeUntilSuccess = timeout) {
      inside(sv1ScanBackend.getRewardAccountingActivityTotals(round)) {
        case GetRewardAccountingActivityTotalsResponse.members
              .RewardAccountingActivityTotalsOk(t) =>
          check(t)
      }
    }

  // Mirrors the pre-amendment sibling test's helper.
  private def fetchEvent(updateId: String, label: String)(implicit
      env: SpliceTestConsoleEnvironment
  ): definitions.EventHistoryItem =
    clue(s"Fetch event $label") {
      eventually() {
        sv1ScanBackend
          .getEventById(updateId, None)
          .getOrElse(fail(s"scan.getEventById($updateId) returned None"))
      }
    }

  // Runs transferPreapprovalSend and returns the committed updateId by
  // reading the single new transaction in the sender's offset range.
  // retryCommandSubmission tolerates races with RenewTransferPreapprovalTrigger
  // archiving the receiver's preapproval mid-flight; each retry uses a fresh
  // deduplicationId so command-dedup doesn't short-circuit.
  private def transferPreapprovalSendCapturingUpdateId(
      senderWallet: org.lfdecentralizedtrust.splice.console.WalletAppClientReference,
      senderParty: PartyId,
      senderParticipant: ParticipantClientReference,
      receiver: PartyId,
      amount: BigDecimal,
  ): String = {
    val beginOffsetExclusive = senderParticipant.ledger_api.state.end()
    retryCommandSubmission {
      senderWallet.transferPreapprovalSend(receiver, amount, UUID.randomUUID().toString)
    }
    eventually() {
      val ledgerEnd = senderParticipant.ledger_api.state.end()
      ledgerEnd should be > beginOffsetExclusive
      val txFormat = transaction_filter.TransactionFormat(
        eventFormat = Some(
          transaction_filter.EventFormat(
            filtersByParty = Map(senderParty.toLf -> transaction_filter.Filters(Nil)),
            filtersForAnyParty = None,
            verbose = false,
          )
        ),
        transactionShape = transaction_filter.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
      )
      val updates = senderParticipant.ledger_api.updates.updates(
        updateFormat = transaction_filter.UpdateFormat(
          includeTransactions = Some(txFormat),
          includeReassignments = None,
          includeTopologyEvents = None,
        ),
        completeAfter = PositiveInt.MaxValue,
        beginOffsetExclusive = beginOffsetExclusive,
        endOffsetInclusive = Some(ledgerEnd),
      )
      val uids = updates.collect {
        case TransactionWrapper(tx) if tx.events.exists(_.event match {
              case Event.Event.Exercised(e) => e.choice == "TransferPreapproval_Send"
              case _ => false
            }) =>
          tx.updateId
      }
      inside(uids) { case Seq(uid) => uid }
    }
  }

  // Asserts `event`'s asset-record weight for `assetParty` is `expectedPct ± tolerancePct`
  // of the submitter's app-record weight. Per-event, so parallel sends in the
  // same round are independently verifiable.
  private def assertAssetRatioInEvent(
      event: definitions.EventHistoryItem,
      submitter: PartyId,
      assetParty: PartyId,
      expectedPct: Int,
      tolerancePct: Int,
      label: String,
  ): Unit = {
    val appRecords = event.appActivityRecords
      .getOrElse(fail(s"$label: event has no appActivityRecords"))
      .records
    val submitterProto = submitter.toProtoPrimitive
    val submitterBurn = appRecords
      .find(_.party == submitterProto)
      .getOrElse(fail(s"$label: no app record for submitter $submitter"))
      .weight
    val assetRecords = event.assetActivityRecords
      .getOrElse(fail(s"$label: event has no assetActivityRecords"))
      .value
    val assetPartyProto = assetParty.toProtoPrimitive
    val assetWeight = assetRecords
      .find(_.party == assetPartyProto)
      .getOrElse(fail(s"$label: no asset record for $assetParty"))
      .weight
    val actualPct = assetWeight * 100L / submitterBurn
    val lower = (expectedPct - tolerancePct).toLong
    val upper = (expectedPct + tolerancePct).toLong
    actualPct should be >= lower withClue
      s"$label: asset/app ratio $actualPct% should be >= $lower% (expected ~$expectedPct%)"
    actualPct should be <= upper withClue
      s"$label: asset/app ratio $actualPct% should be <= $upper% (expected ~$expectedPct%)"
  }

  // Walks the CIP-0104 reward batch Merkle tree and returns every
  // (provider, amount) pair above the active threshold. Mirrors the
  // pre-amendment sibling test's helper.
  private def getMintingAllowancesForRound(
      round: Long
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Seq[definitions.RewardAccountingMintingAllowance] = {
    val hash = inside(sv1ScanBackend.getRewardAccountingRootHash(round)) {
      case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(h) =>
        h.rootHash
    }
    def walk(h: String): Seq[definitions.RewardAccountingMintingAllowance] =
      sv1ScanBackend.getRewardAccountingBatch(round, h).toList.flatMap {
        case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfBatches(b) =>
          b.childHashes.flatMap(walk)
        case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfMintingAllowances(b) =>
          b.mintingAllowances.toSeq
      }
    walk(hash)
  }
}

object TrafficBasedRewardsV11TimeBasedIntegrationTestBase {

  sealed trait RewardConfigMode
  object RewardConfigMode {
    case object DryRun extends RewardConfigMode
    case object MintingTrafficBased extends RewardConfigMode
  }

  val trafficBasedAppRewards = "RewardVersion_TrafficBasedAppRewards"
  val featuredAppMarkers = "RewardVersion_FeaturedAppMarkers"

  // Matches the pre-amendment test's threshold so pre-/post-amendment baselines align.
  val appRewardCouponThreshold = BigDecimal("50")
}

class TrafficBasedRewardsV11TimeBasedIntegrationTest
    extends TrafficBasedRewardsV11TimeBasedIntegrationTestBase {
  override protected val rewardConfigMode
      : TrafficBasedRewardsV11TimeBasedIntegrationTestBase.RewardConfigMode =
    TrafficBasedRewardsV11TimeBasedIntegrationTestBase.RewardConfigMode.MintingTrafficBased
}

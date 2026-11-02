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
    // All asset-side setup (party onboarding, FA grants with kinds, RewardConfig
    // enable, TransferPreapproval, tap) happens at bootstrap simtime so that
    // every OpenMiningRound created after bootstrap snapshots the updated
    // AmuletConfig and so that lookupFeaturedAppPartiesAsOf(roundOpensAt) returns
    // the FAK_ASSETMOVING / FAK_ASSET grants for the round that observes the send.
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
    // Second FAK_ASSET party at weight=3 so one send can exercise the weight-1 (default)
    // and the weight=3 (step-8-scaling) paths in parallel, within a single cap round.
    // charlie runs on alice's validator per the simple-topology-1sv config.
    val highWeightAssetParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
    clue(s"Grant FAK_Asset right (weight=3) to $highWeightAssetParty") {
      grantFeaturedAppRightWithKind(
        highWeightAssetParty,
        FeaturedAppKind.FAK_ASSET,
        opsParties = Seq(aliceValidatorBackend.getValidatorPartyId()),
        weight = BigDecimal(3),
      )
    }
    // Third FAK_ASSET party whose grant will be revoked between the baseline and
    // post-vote scenario rounds. Lets both the pre-revocation (baseline-equivalent)
    // and post-revocation (asset-side absence) assertions land in the same two
    // scenario rounds as the other sends.
    val doomedAssetParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
    clue(s"Grant FAK_Asset right (weight=1) to $doomedAssetParty (will be revoked mid-flow)") {
      grantReceiverOnlyFeaturedAppRight(doomedAssetParty, FeaturedAppKind.FAK_ASSET)
    }
    // Venue party for DvP settlements. Hosted on splitwellValidator with a party
    // hint distinct from splitwell's wallet-user (which is used as the non-FA
    // submitter in round 5 and must not collide). Venue holds FAK_ASSETMOVING so
    // its OTCTrade_Settle submissions resolve to the FA submitter in the compute,
    // triggering computeAssetActivity.
    //
    // The venue is onboarded under a fresh ledger-api user, with
    // createIfMissing = true so the hinted party is actually allocated on
    // splitwellParticipant. Without that flag onboardUser only binds the user to
    // the hinted PartyId without allocating it, and subsequent amulet
    // submissions fail with UNKNOWN_SUBMITTERS. Reusing splitwellWalletClient's
    // user would also go wrong a different way — onboardUser would silently
    // return the existing primary party (splitwell__provider) and the hint
    // would be ignored, making venueParty identical to the non-FA sender party.
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

    // Receivers' TransferPreapprovals so the sender submits the amulet transfer
    // directly — assetMoverParty becomes the Daml submitter, and findFASubmitter
    // attributes the transaction to the FAK_ASSETMOVING grant.
    bobWalletClient.createTransferPreapproval()
    charlieWalletClient.createTransferPreapproval()
    sv1WalletClient.createTransferPreapproval()
    aliceWalletClient.tap(1000.0)
    // bob also needs amulet so he can submit a transfer himself (FAK_ASSET-as-submitter
    // scenario in the post-vote round — exercises the compute path that skips
    // computeAssetActivity for non-ASSETMOVING FA submitters).
    bobWalletClient.tap(1000.0)
    // charlie and sv1 are counterparties in DvP legs (they send their own amulet
    // for leg1 of each OTCTrade). Fund both so the allocation can lock.
    charlieWalletClient.tap(1000.0)
    sv1WalletClient.tap(1000.0)

    // Non-FA submitter for the baseline round's negative-case leg: splitwell's
    // wallet user never gets a FA grant, so a transfer it submits should miss
    // both findFASubmitter paths and fall through to computeForUserSubmitted
    // (which produces app records but no asset records).
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

    // Sequence of actions
    //   Round  | Scenario / mid-flow votes
    //   -------+--------------------------------------------------------
    //   1-4    | propagation (bootstrap config lands in OpenMiningRounds)
    //   5      | 2 DvP settlements + 1 non-FA direct send
    //   6-7    | close round 5
    //   7-ish  | mid-flow votes: cap→0.05, threshold→5 USD, revoke doomed
    //   8-10   | propagation
    //   10     | 3 DvP settlements + 1 FAK_ASSET-submitter direct send
    //   11-12  | close round 10

    val baselineRound = 5L
    val postVoteRound = 10L

    // CalculateRewardsTrigger and its dry-run twin are paused across the whole
    // scenario body so the per-round CalculateRewardsV2 contracts are observable
    // before being consumed, matching the pre-amendment sibling test.
    val calculateRewardsTriggers =
      activeSvs.map(_.dsoAutomation.trigger[CalculateRewardsTrigger])
    val calculateRewardsDryRunTriggers =
      activeSvs.map(_.dsoAutomation.trigger[CalculateRewardsDryRunTrigger])

    val (uid5Dvp1, uid5Direct, uid5Dvp2, uid10Dvp1, uid10Dvp2, uid10Dvp3, uid10Direct) =
      setTriggersWithin(triggersToPauseAtStart =
        calculateRewardsTriggers ++ calculateRewardsDryRunTriggers
      ) {
        // Advance several rounds so the new TBAR v1.1 RewardConfig lands in the
        // OpenMiningRound snapshots of upcoming rounds.
        for (round <- 1 to 5) {
          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(round.toLong)
        }
        pauseScanVerdictIngestionWithin(sv1ScanBackend) {

          // Round 5 scenario: all cap=0.1 cases.
          // - DvP alice↔bob (venue submits, FAK_ASSETMOVING): baseline positive. Bob
          //   (FAK_ASSET weight=1) is an asset confirmer across the leg envelopes.
          // - splitwell→bob direct send (non-FA submitter): negative; app records only,
          //   no asset-side (findFASubmitter returns None → computeForFASubmitted never
          //   runs). Direct transfer because DvP always resolves to the venue submitter;
          //   this scenario needs splitwell to be the Daml submitter.
          // - DvP alice↔doomedAssetParty (venue submits): same baseline ratio as alice↔bob
          //   while doomedAssetParty still has its grant. The revocation voted below
          //   between rounds 5 and 10 makes the parallel round-10 send to doomedAssetParty
          //   exercise the post-revocation path.
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
          for (round <- 6 to 7) {
            advanceTimeAndWaitForRoundOpening
            assertOldestOpenRound(round.toLong)
          }

          // Mid-flow: three governance changes share the same propagation window.
          // - Halve the per-asset cap: round-10 scenario exercises cap clamping (w=1)
          //   and step-8 weight scaling (w=3) via two parallel sends.
          // - Revoke doomedAssetParty's FA grant: round-10 scenario also exercises
          //   the post-revocation absence via a parallel alice→doomedAssetParty send.
          // - Lower appRewardCouponThreshold: in round 10, alice's combined
          //   (app+asset) reward clears the lowered threshold while bob/charlie
          //   (asset-only, lower per-party totals) fall below — exercising the
          //   thresholding/minting-allowance split in a single round.
          clue("Lower per-asset cap, lower threshold, and revoke doomedAssetParty in one window") {
            updateTbarV11RewardConfig(
              maxExtraFeaturedAssetActivityWeightPerAsset = BigDecimal("0.05"),
              appRewardCouponThreshold = BigDecimal("5"),
            )
            revokeFeaturedAppRight(doomedAssetParty)
          }
          for (round <- 8 to 10) {
            advanceTimeAndWaitForRoundOpening
            assertOldestOpenRound(round.toLong)
          }

          // Round 10 scenario: four cap=0.05 cases.
          // - DvP alice↔assetParty (venue submits): exercises cap clamp.
          // - DvP alice↔highWeightAssetParty (venue submits): exercises step-8 weight
          //   application atop the same cap.
          // - DvP alice↔doomedAssetParty (venue submits): post-revocation attribution —
          //   doomedAssetParty is no longer a FAK_ASSET confirmer so its asset activity
          //   record is absent; only DSO's share remains, re-attributed to the venue.
          // - bob→highWeightAssetParty direct send (FAK_ASSET submitter): exercises the
          //   compute path that recognises bob as the FA submitter but skips
          //   computeAssetActivity (only FAK_ASSETMOVING submitters trigger the asset
          //   split). Direct transfer because DvP always resolves to venue; this
          //   scenario needs bob as the Daml submitter.
          val id10Dvp1 = settleTrade(
            aliceWalletClient,
            assetMoverParty,
            bobWalletClient,
            assetParty,
            venueParty,
            walletUsdToAmulet(100.0),
            walletUsdToAmulet(20.0),
          )
          val id10Dvp2 = settleTrade(
            aliceWalletClient,
            assetMoverParty,
            charlieWalletClient,
            highWeightAssetParty,
            venueParty,
            walletUsdToAmulet(100.0),
            walletUsdToAmulet(20.0),
            aliceValidatorBackend,
          )
          val id10Dvp3 = settleTrade(
            aliceWalletClient,
            assetMoverParty,
            sv1WalletClient,
            doomedAssetParty,
            venueParty,
            walletUsdToAmulet(100.0),
            walletUsdToAmulet(20.0),
            sv1ValidatorBackend,
          )
          val id10Direct = transferPreapprovalSendCapturingUpdateId(
            senderWallet = bobWalletClient,
            senderParty = assetParty,
            senderParticipant = bobValidatorBackend.participantClientWithAdminToken,
            receiver = highWeightAssetParty,
            amount = BigDecimal(5),
          )
          for (round <- 11 to 12) {
            advanceTimeAndWaitForRoundOpening
            assertOldestOpenRound(round.toLong)
          }

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
      // When a FAK_ASSET party submits (vs. only confirms), computeForFASubmitted
      // recognises them via findFASubmitter but the match guard skips
      // computeAssetActivity — only FAK_ASSETMOVING submitters trigger the
      // asset-side split. Submitter gets the full burn as a single app record;
      // assetActivityRecords stays absent for this verdict, even for FAK_ASSET
      // confirmers like highWeightAssetParty.
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
      // The round-closing path does not yet produce a value for
      // optIssuancePerFeaturedAssetRewardCoupon from the asset-activity totals,
      // so the field stays None on IssuingMiningRound. Pin that invariant: when
      // the round-close summary starts populating the field, this assertion will
      // fire and should be tightened to the CIP-104 formula value.
      //
      // Must run before phase 2a — the extra 7 rounds advanced during the
      // diagnostic cause round 10's IssuingMiningRound to age past
      // ExpireIssuingMiningRoundTrigger and get archived.
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

    // Phase 2a: measure the scenario's per-party reward distribution under a
    // near-zero threshold so every party clears and shows up in the batch tree.
    // The measured amounts predict phase 2b's per-party amounts because the
    // scenario replay is identical and per-party reward amounts only depend on
    // per-party activity weights (not on the threshold, which only filters at
    // the end). Diagnostic C-run5 confirmed the distribution is stable across
    // identical scenario replays within ±~100 CC.
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
          for (round <- 13 to 15) {
            advanceTimeAndWaitForRoundOpening
            assertOldestOpenRound(round.toLong)
          }

          // Replay the round-10 scenario identically.
          val id15Dvp1 = settleTrade(
            aliceWalletClient,
            assetMoverParty,
            bobWalletClient,
            assetParty,
            venueParty,
            walletUsdToAmulet(100.0),
            walletUsdToAmulet(20.0),
          )
          val id15Dvp2 = settleTrade(
            aliceWalletClient,
            assetMoverParty,
            charlieWalletClient,
            highWeightAssetParty,
            venueParty,
            walletUsdToAmulet(100.0),
            walletUsdToAmulet(20.0),
            aliceValidatorBackend,
          )
          val id15Dvp3 = settleTrade(
            aliceWalletClient,
            assetMoverParty,
            sv1WalletClient,
            doomedAssetParty,
            venueParty,
            walletUsdToAmulet(100.0),
            walletUsdToAmulet(20.0),
            sv1ValidatorBackend,
          )
          val id15Direct = transferPreapprovalSendCapturingUpdateId(
            senderWallet = bobWalletClient,
            senderParty = assetParty,
            senderParticipant = bobValidatorBackend.participantClientWithAdminToken,
            receiver = highWeightAssetParty,
            amount = BigDecimal(5),
          )
          for (round <- 16 to 17) {
            advanceTimeAndWaitForRoundOpening
            assertOldestOpenRound(round.toLong)
          }
          (id15Dvp1, id15Dvp2, id15Dvp3, id15Direct)
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
    // appRewardCouponThreshold in RewardConfig is denominated in USD; scan
    // converts to CC via `threshold_CC = threshold_USD / amuletPrice`
    // (RewardComputationInputs.scala:96). getMintingAllowancesForRound returned
    // CC amounts, so convert the calibrated CC midpoint back to USD before
    // voting. Read amuletPrice from the latest OpenMiningRound so the test
    // doesn't bake in the env default.
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

    // Phase 2b: vote the calibrated threshold, replay the scenario in round 20,
    // and assert the mixed above/below branch — the smallest party falls below
    // the midpoint-of-two-smallest threshold while all others clear.
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
          for (round <- 18 to 20) {
            advanceTimeAndWaitForRoundOpening
            assertOldestOpenRound(round.toLong)
          }

          val id20Dvp1 = settleTrade(
            aliceWalletClient,
            assetMoverParty,
            bobWalletClient,
            assetParty,
            venueParty,
            walletUsdToAmulet(100.0),
            walletUsdToAmulet(20.0),
          )
          val id20Dvp2 = settleTrade(
            aliceWalletClient,
            assetMoverParty,
            charlieWalletClient,
            highWeightAssetParty,
            venueParty,
            walletUsdToAmulet(100.0),
            walletUsdToAmulet(20.0),
            aliceValidatorBackend,
          )
          val id20Dvp3 = settleTrade(
            aliceWalletClient,
            assetMoverParty,
            sv1WalletClient,
            doomedAssetParty,
            venueParty,
            walletUsdToAmulet(100.0),
            walletUsdToAmulet(20.0),
            sv1ValidatorBackend,
          )
          val id20Direct = transferPreapprovalSendCapturingUpdateId(
            senderWallet = bobWalletClient,
            senderParty = assetParty,
            senderParticipant = bobValidatorBackend.participantClientWithAdminToken,
            receiver = highWeightAssetParty,
            amount = BigDecimal(5),
          )
          for (round <- 21 to 22) {
            advanceTimeAndWaitForRoundOpening
            assertOldestOpenRound(round.toLong)
          }
          (id20Dvp1, id20Dvp2, id20Dvp3, id20Direct)
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
  // Optional fields and (optionally) overrides the base appRewardCouponThreshold.
  // InitialRewardConfig leaves the TBAR v1.1 fields None at bootstrap, so until
  // this runs computeForFASubmitted short-circuits (no asset activity records).
  // Defaults match the CIP-104 recommended values plus the test's bootstrap
  // threshold; override individual fields to exercise different configurations
  // (e.g. a lower per-asset cap or a lowered threshold where some parties clear
  // and others fall below).
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

  // Grants a featured-app right directly with the specified kind via
  // DsoRules_GrantFeaturedAppRight (controller: dso). The opsParties argument
  // registers additional parties (typically the user's validator operator) that
  // submit on behalf of the provider — findFASubmitter resolves each submitter
  // against the FA providers' opsParty mapping, so the validator party must be
  // listed here for wallet-submitted transactions to be attributed to the user's
  // FA grant. Blocks until scan observes the right with the expected kind and weight.
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

  // Grants an FA right for a party that will only ever appear as a receiver /
  // confirmer, never as a submitter. In that case no opsParties entry is needed
  // (findFASubmitter is never consulted for this party). Also enforces the
  // Amulet.daml `validFeaturedAppRight` constraint which requires
  // `provider notElem opsParties` — useful when the provider party coincides
  // with its own validator-operator party (e.g. an SV wallet in a 1-SV topology).
  private def grantReceiverOnlyFeaturedAppRight(
      party: PartyId,
      kind: FeaturedAppKind,
      weight: BigDecimal = BigDecimal(1),
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    grantFeaturedAppRightWithKind(party, kind, opsParties = Seq.empty, weight = weight)

  // Votes a DsoRules_RevokeFeaturedAppRight choice, which withdraws the FA right.
  // Blocks until scan no longer observes the right for `party`. From this point
  // lookupFeaturedAppPartiesAsOf will not surface `party` as a provider for any
  // subsequent round opening — the compute's findFASubmitter / asset-confirmer
  // paths will treat `party` as non-featured.
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

  // Waits (eventually) for scan to return RewardAccountingActivityTotalsOk for the
  // given round and then runs `check` against the totals. Fails loudly if scan
  // keeps responding with Undetermined/CannotProvide once the timeout elapses.
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

  // Resolves a specific event by its updateId, wrapping the OrElse-fail boilerplate
  // and labelling the lookup. Mirrors the pre-amendment sibling test's helper.
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

  // Runs a transferPreapprovalSend and returns the committed transaction's updateId.
  // Captures the sender participant's ledger offset before the send, then reads back
  // the single new transaction from the sender's participant filtered by sender party.
  // Background wallet/validator automation is paused in this test's env, so the
  // sender's party only shows the one submission we expect in this window.
  //
  // The submission is wrapped in retryCommandSubmission to tolerate races with
  // RenewTransferPreapprovalTrigger: over the long simtime this test spans, scan
  // can hand out a receiver-preapproval contract id that is archived by renewal
  // before the sender's command reaches the participant. Each retry generates a
  // fresh deduplicationId so Canton's command-dedup doesn't short-circuit the
  // next attempt, and failed submissions don't commit so the offset-range search
  // still finds the single successful transaction.
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

  // Asserts that `event`'s per-record asset weight for `assetParty` matches
  // `expectedPct` of the submitter's app weight (= total burn attributed to the
  // submitter in computeForFASubmitted), within ± tolerancePct. The ratio check
  // is per-event, so parallel sends in the same round can each be verified
  // against their own CIP-formula expectation independently.
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

  // Walks the CIP-0104 reward batch Merkle tree from the root hash to the
  // leaves and returns every per-party (provider, amount) pair. Only parties
  // whose combined reward cleared the active appRewardCouponThreshold appear
  // in the batch; below-threshold parties are filtered out upstream in scan's
  // stage-2 SQL. Mirrors the pre-amendment sibling test's helper.
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

  // Picked to match the pre-amendment test's threshold so the pre-/post-amendment
  // numeric baselines line up when comparing scenarios.
  val appRewardCouponThreshold = BigDecimal("50")
}

class TrafficBasedRewardsV11TimeBasedIntegrationTest
    extends TrafficBasedRewardsV11TimeBasedIntegrationTestBase {
  override protected val rewardConfigMode
      : TrafficBasedRewardsV11TimeBasedIntegrationTestBase.RewardConfigMode =
    TrafficBasedRewardsV11TimeBasedIntegrationTestBase.RewardConfigMode.MintingTrafficBased
}

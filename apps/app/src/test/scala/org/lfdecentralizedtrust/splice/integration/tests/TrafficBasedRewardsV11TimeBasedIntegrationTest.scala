package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.topology.PartyId
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
import definitions.DamlValueEncoding.members.CompactJson
import definitions.GetRewardAccountingActivityTotalsResponse
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
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

// Sibling to TrafficBasedRewardsTimeBasedIntegrationTest covering TBAR v1.1
// (asset-side activity introduced by the CIP-104 amendment). The pre-amendment
// test is kept as a regression reference per the author's and reviewer's
// 2026-10-28 agreement on #7837.
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
    // sv1's SV party, validator party, and wallet-user party are all the same
    // party in simple-topology-1sv, so opsParties MUST be empty: Amulet.daml's
    // validFeaturedAppRight requires `provider notElem opsParties`. The party is
    // only ever a receiver here, never a submitter, so no ops-party resolution
    // in findFASubmitter is needed.
    val doomedAssetParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
    clue(s"Grant FAK_Asset right (weight=1) to $doomedAssetParty (will be revoked mid-flow)") {
      grantFeaturedAppRightWithKind(
        doomedAssetParty,
        FeaturedAppKind.FAK_ASSET,
        opsParties = Seq.empty,
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

    // Non-FA submitter for the baseline round's negative-case leg: splitwell's
    // wallet user never gets a FA grant, so a transfer it submits should miss
    // both findFASubmitter paths and fall through to computeForUserSubmitted
    // (which produces app records but no asset records).
    onboardWalletUser(splitwellWalletClient, splitwellValidatorBackend)
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
    //   5      | 3 parallel sends: baseline + non-FA + pre-revoke-baseline
    //   6-7    | close round 5
    //   7-ish  | mid-flow votes: cap→0.05, threshold→5 USD, revoke doomed
    //   8-10   | propagation
    //   10     | 3 parallel sends: cap-crossed + weight-tripled + post-revoke
    //   11-12  | close round 10

    // Advance several rounds so the new TBAR v1.1 RewardConfig lands in the
    // OpenMiningRound snapshots of upcoming rounds.
    for (round <- 1 to 5) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    // Round 5 scenario: all cap=0.1 cases in parallel.
    // - alice→bob (FA submitter + FAK_ASSET confirmer, weight=1): baseline positive.
    // - splitwell→bob (non-FA submitter, FAK_ASSET confirmer): negative; app records
    //   only, no asset-side (findFASubmitter returns None → computeForFASubmitted
    //   never runs).
    // - alice→doomedAssetParty (FA submitter + FAK_ASSET confirmer, weight=1): same
    //   expected ratio as alice→bob while doomedAssetParty still has its grant. The
    //   revocation voted below between rounds 5 and 10 makes the parallel round-10
    //   send to doomedAssetParty exercise the post-revocation path.
    val baselineRound = 5L
    val cursorBeforeBaseline = eventuallySucceeds() { latestEventHistoryCursor(sv1ScanBackend) }
    clue(s"Round $baselineRound: three parallel sends (FA, non-FA, FA-to-doomed)") {
      aliceWalletClient.transferPreapprovalSend(
        assetParty,
        BigDecimal(10),
        UUID.randomUUID().toString,
      )
      splitwellWalletClient.transferPreapprovalSend(
        assetParty,
        BigDecimal(5),
        UUID.randomUUID().toString,
      )
      aliceWalletClient.transferPreapprovalSend(
        doomedAssetParty,
        BigDecimal(10),
        UUID.randomUUID().toString,
      )
    }
    for (round <- 6 to 7) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    val baselineEvents = sv1ScanBackend.getEventHistory(
      count = 1000,
      after = Some(cursorBeforeBaseline),
      encoding = CompactJson,
    )

    clue(
      s"Round $baselineRound FA event: per-record asset weight for $assetParty is ~10% of burn (cap=0.1, weight=1)"
    ) {
      assertFaSendRatio(
        events = baselineEvents,
        submitter = assetMoverParty,
        assetParty = assetParty,
        expectedPct = 10,
        tolerancePct = 3,
        label = s"baseline alice→$assetParty",
      )
    }

    clue(
      s"Round $baselineRound FA event: per-record asset weight for $doomedAssetParty is ~10% of burn (same baseline — still active)"
    ) {
      assertFaSendRatio(
        events = baselineEvents,
        submitter = assetMoverParty,
        assetParty = doomedAssetParty,
        expectedPct = 10,
        tolerancePct = 3,
        label = s"baseline alice→$doomedAssetParty (pre-revocation)",
      )
    }

    clue(
      s"Round $baselineRound non-FA event (splitwell→$assetParty): app records only, no asset activity"
    ) {
      val userSubmittedEvent = baselineEvents
        .find(e =>
          e.appActivityRecords.exists(_.records.nonEmpty) && e.assetActivityRecords.isEmpty
        )
        .getOrElse(
          fail(
            s"Expected a user-submitted event with app records (and no asset records) in round $baselineRound events"
          )
        )
      val noFaUpdateId = userSubmittedEvent.verdict
        .map(_.updateId)
        .getOrElse(fail("User-submitted event should carry a verdict with an updateId"))
      val noFaEvent = fetchEvent(noFaUpdateId, s"non-FA $noFaUpdateId")
      noFaEvent.submittingAppProvider shouldBe None withClue
        s"Non-FA-submitted event $noFaUpdateId should have no submitting_app_provider"
    }

    clue(
      s"Round $baselineRound FA event (alice→$assetParty): getEventById reports submitting_app_provider = $assetMoverParty"
    ) {
      val faEvent = findFaSubmittedEvent(baselineEvents, assetParty)
      val faUpdateId = faEvent.verdict
        .map(_.updateId)
        .getOrElse(fail("FA-submitted event should carry a verdict with an updateId"))
      val reloaded = fetchEvent(faUpdateId, s"FA $faUpdateId")
      reloaded.submittingAppProvider.map(_.value) shouldBe Some(
        assetMoverParty.toProtoPrimitive
      ) withClue
        s"FA-submitted event $faUpdateId should report $assetMoverParty as submitting_app_provider"
    }

    // Mid-flow: three governance changes share the same propagation window.
    // - Halve the per-asset cap: round-10 scenario exercises cap clamping (w=1) and
    //   step-8 weight scaling (w=3) via two parallel sends.
    // - Revoke doomedAssetParty's FA grant: round-10 scenario also exercises the
    //   post-revocation absence via a parallel alice→doomedAssetParty send.
    // - Lower appRewardCouponThreshold: in round 10, alice's combined (app+asset)
    //   reward clears the lowered threshold while bob/charlie (asset-only, lower
    //   per-party totals) fall below — exercising the thresholding/minting-allowance
    //   split in a single round.
    clue("Lower per-asset cap, lower threshold, and revoke doomedAssetParty in one window") {
      updateTbarV11RewardConfig(
        maxExtraFeaturedAssetActivityWeightPerAsset = BigDecimal("0.05"),
        appRewardCouponThreshold = BigDecimal("5"),
      )
      revokeFeaturedAppRight(doomedAssetParty)
    }
    for (round <- 8 to 10) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    // Round 10 scenario: three cap=0.05 cases in parallel.
    // - alice→assetParty (weight=1): exercises cap clamp (~5% per-record asset ratio).
    // - alice→highWeightAssetParty (weight=3): exercises step-8 weight application
    //   atop the same cap (~15% per-record asset ratio).
    // - alice→doomedAssetParty (revoked): exercises post-revocation attribution —
    //   doomedAssetParty is no longer a FAK_ASSET confirmer so its asset activity
    //   record is absent; only DSO's share remains, re-attributed to the submitter.
    val postVoteRound = 10L
    val cursorBeforePostVote = eventuallySucceeds() { latestEventHistoryCursor(sv1ScanBackend) }
    clue(
      s"Round $postVoteRound: cap-crossed (w=1) + weight-tripled (w=3) + post-revocation (doomed) in parallel"
    ) {
      aliceWalletClient.transferPreapprovalSend(
        assetParty,
        BigDecimal(10),
        UUID.randomUUID().toString,
      )
      aliceWalletClient.transferPreapprovalSend(
        highWeightAssetParty,
        BigDecimal(10),
        UUID.randomUUID().toString,
      )
      aliceWalletClient.transferPreapprovalSend(
        doomedAssetParty,
        BigDecimal(10),
        UUID.randomUUID().toString,
      )
    }
    for (round <- 11 to 12) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    val postVoteEvents = sv1ScanBackend.getEventHistory(
      count = 1000,
      after = Some(cursorBeforePostVote),
      encoding = CompactJson,
    )

    clue(
      s"Round $postVoteRound cap-crossed event (alice→$assetParty, w=1, cap=0.05): per-record asset ratio ~5%"
    ) {
      assertFaSendRatio(
        events = postVoteEvents,
        submitter = assetMoverParty,
        assetParty = assetParty,
        expectedPct = 5,
        tolerancePct = 3,
        label = s"cap-crossed alice→$assetParty",
      )
    }

    clue(
      s"Round $postVoteRound weight-tripled event (alice→$highWeightAssetParty, w=3, cap=0.05): per-record asset ratio ~15%"
    ) {
      assertFaSendRatio(
        events = postVoteEvents,
        submitter = assetMoverParty,
        assetParty = highWeightAssetParty,
        expectedPct = 15,
        tolerancePct = 4,
        label = s"weight-tripled alice→$highWeightAssetParty",
      )
    }

    clue(
      s"Round $postVoteRound post-revocation event (alice→$doomedAssetParty): no asset record for the revoked party; only DSO-share re-attributed to $assetMoverParty"
    ) {
      // The revoked-receiver send is identified by its asset-records shape:
      // sends to active FAK_ASSET confirmers in this round have two entries
      // (confirmer + submitter); the revoked send has only one (submitter).
      val assetPartyProtos = findDsoShareOnlyAssetPartyProtos(postVoteEvents, assetMoverParty)
      assetPartyProtos should not contain doomedAssetParty.toProtoPrimitive withClue
        s"$doomedAssetParty was revoked; its FA grant should no longer attribute asset activity"
      assetPartyProtos shouldBe Set(assetMoverParty.toProtoPrimitive) withClue
        s"Only DSO's share should remain, re-attributed to the submitting app $assetMoverParty"
    }

    clue(
      s"Round $postVoteRound combined-threshold mix of above/below (threshold lowered to 5 USD mid-flow)"
    ) {
      // With the mid-flow vote lowering appRewardCouponThreshold to 5 USD:
      // - assetMoverParty's combined (app + DSO-share asset) reward across 3
      //   submitter envelopes clears 5 USD → contributes to minting allowance.
      // - assetParty / highWeightAssetParty (asset-only, per-send, lower per-party
      //   totals) fall below 5 USD → contribute to thresholded.
      // Pinning both non-zero exercises the mixed above/below branch — any
      // regression that pushed all parties into one bucket (all above or all
      // below) would flip one of these to zero.
      assertRoundTotalsOk(postVoteRound) { t =>
        BigDecimal(t.totalAppRewardMintingAllowance) should be > BigDecimal(0) withClue
          s"Round $postVoteRound: at least one party should clear the lowered 5 USD threshold"
        BigDecimal(t.totalAppRewardThresholded) should be > BigDecimal(0) withClue
          s"Round $postVoteRound: at least one party should fall below the lowered 5 USD threshold"
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
  // ever responds with Undetermined/CannotProvide once the timeout elapses.
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

  // Picks the first event whose assetActivityRecords includes `assetParty` as a
  // record party. Used to disambiguate parallel per-round sends by their target
  // asset provider rather than by updateId (which transferPreapprovalSend does
  // not return).
  private def findFaSubmittedEvent(
      events: Seq[definitions.EventHistoryItem],
      assetParty: PartyId,
  ): definitions.EventHistoryItem = {
    val partyProto = assetParty.toProtoPrimitive
    events
      .find(_.assetActivityRecords.exists(_.value.exists(_.party == partyProto)))
      .getOrElse(
        fail(s"No event found with $assetParty as an asset activity record party")
      )
  }

  // Picks out the asset-party set of the first FA-submitted event in `events`
  // whose assetActivityRecords contain only the submitter (i.e., no FAK_ASSET
  // confirmer in the envelope — only the DSO's share, re-attributed to the
  // submitting app). Used to identify post-revocation sends whose intended
  // receiver no longer holds a FAK_ASSET grant.
  private def findDsoShareOnlyAssetPartyProtos(
      events: Seq[definitions.EventHistoryItem],
      submitter: PartyId,
  ): Set[String] = {
    val submitterProto = submitter.toProtoPrimitive
    events.iterator
      .flatMap { e =>
        for {
          _ <- e.appActivityRecords.filter(_.records.exists(_.party == submitterProto))
          asset <- e.assetActivityRecords.filter(r =>
            r.value.sizeIs == 1 && r.value.exists(_.party == submitterProto)
          )
        } yield asset.value.map(_.party).toSet
      }
      .nextOption()
      .getOrElse(
        fail(s"Expected an FA-submitted event with only $submitter in assetActivityRecords")
      )
  }

  // Resolves a specific event by its updateId, wrapping the OrElse-fail boilerplate
  // and labelling the lookup. Mirrors the pre-amendment sibling test's helper.
  private def fetchEvent(updateId: String, label: String)(implicit
      env: SpliceTestConsoleEnvironment
  ): definitions.EventHistoryItem =
    clue(s"Fetch event $label") {
      sv1ScanBackend
        .getEventById(updateId, None)
        .getOrElse(fail(s"scan.getEventById($updateId) returned None"))
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

  // Convenience that pairs findFaSubmittedEvent with assertAssetRatioInEvent for
  // the common "find alice→X event, then assert its ratio" pattern.
  private def assertFaSendRatio(
      events: Seq[definitions.EventHistoryItem],
      submitter: PartyId,
      assetParty: PartyId,
      expectedPct: Int,
      tolerancePct: Int,
      label: String,
  ): Unit =
    assertAssetRatioInEvent(
      event = findFaSubmittedEvent(events, assetParty),
      submitter = submitter,
      assetParty = assetParty,
      expectedPct = expectedPct,
      tolerancePct = tolerancePct,
      label = label,
    )
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

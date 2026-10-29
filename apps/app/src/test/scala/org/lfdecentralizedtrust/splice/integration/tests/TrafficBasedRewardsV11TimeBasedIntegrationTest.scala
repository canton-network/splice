package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.topology.PartyId
import java.time.Duration
import java.util.{Optional, UUID}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  FeaturedAppKind,
  FeaturedAppRight_Update,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  RewardConfig,
  USD,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_GrantFeaturedAppRight,
  DsoRules_UpdateFeaturedAppRight,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_GrantFeaturedAppRight,
  SRARC_UpdateFeaturedAppRight,
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
    updateTbarV11RewardConfig()

    // Receiver's TransferPreapproval is required so the sender submits the
    // amulet transfer directly — makes assetMoverParty the Daml submitter so
    // findFASubmitter attributes the transaction to the FAK_ASSETMOVING grant.
    bobWalletClient.createTransferPreapproval()
    aliceWalletClient.tap(1000.0)

    // Non-FA submitter for the negative-case clue below: splitwell's wallet user
    // never gets a FA grant, so a transfer it submits should miss both
    // findFASubmitter paths and fall through to computeForUserSubmitted (which
    // produces app records but no asset records).
    val nonFaSubmitterParty = onboardWalletUser(splitwellWalletClient, splitwellValidatorBackend)
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

    // Advance several rounds so the new TBAR v1.1 RewardConfig lands in the
    // OpenMiningRound snapshots of upcoming rounds.
    for (round <- 1 to 5) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    val sendRound = 5L
    // Scan returns events in ascending time; cursor-before-send bounds later queries
    // to the post-send window instead of paging through the full history.
    val cursorBeforeSend = eventuallySucceeds() { latestEventHistoryCursor(sv1ScanBackend) }
    clue("CIP-104 Example 2.1: assetMoverParty sends to assetParty (sole FAK_ASSET confirmer)") {
      aliceWalletClient.transferPreapprovalSend(
        assetParty,
        BigDecimal(10),
        UUID.randomUUID().toString,
      )
    }

    // Advance to round 6 so the negative-case send below lands in a distinct round
    // from the FA-submitted send above.
    advanceRoundsToNextRoundOpening
    assertOldestOpenRound(6L)

    val sendRoundNoFa = 6L
    val cursorBeforeNoFaSend = eventuallySucceeds() { latestEventHistoryCursor(sv1ScanBackend) }
    clue(
      s"CIP-104 negative: non-FA submitter ($nonFaSubmitterParty) produces app-only, no asset activity in round $sendRoundNoFa"
    ) {
      splitwellWalletClient.transferPreapprovalSend(
        assetParty,
        BigDecimal(5),
        UUID.randomUUID().toString,
      )
    }

    // Advance past both send rounds so their totals are computed and exposed by scan.
    for (round <- 7 to 8) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    // Events generated by the FA-submitted send and the round-5-closing automation.
    // Fetched once and reused by the two downstream clues below.
    val postSendEvents = sv1ScanBackend.getEventHistory(
      count = 1000,
      after = Some(cursorBeforeSend),
      encoding = CompactJson,
    )

    clue(s"Round $sendRound shows asset-side TBAR v1.1 activity from the asset-moving send") {
      assertRoundTotalsOk(sendRound) { t =>
        t.activityRecordsCount should be >= 1L withClue
          s"Round $sendRound should have at least one activity record from the send"
        t.totalAppActivityWeight should be > 0L withClue
          s"Round $sendRound should have non-zero app activity weight (total burn attributed to submitter)"
        // CIP-104 asset split: extraFeaturedAssetActivityWeight=0.2 is the headline,
        // but per-envelope split, the per-asset cap (0.1), and dso-weight attenuation
        // land the aggregate near ~0.11×. The [5%, 25%] window catches both the
        // "zero asset activity" regression and >1× inflation.
        val assetLower = (BigDecimal(t.totalAppActivityWeight) * 0.05).toLong
        val assetUpper = (BigDecimal(t.totalAppActivityWeight) * 0.25).toLong
        t.totalAssetActivityWeight should be >= assetLower withClue
          s"Round $sendRound asset activity weight (${t.totalAssetActivityWeight}) should be at least 5% of app activity weight (${t.totalAppActivityWeight})"
        t.totalAssetActivityWeight should be <= assetUpper withClue
          s"Round $sendRound asset activity weight (${t.totalAssetActivityWeight}) should be at most 25% of app activity weight (${t.totalAppActivityWeight})"
      }
    }

    clue(s"At least one event exposes assetParty $assetParty via per-event assetActivityRecords") {
      // Shape-1 field end-to-end: the OmitNullArray-wrapped array from the event-api PR.
      val eventsWithAssetRecords =
        postSendEvents.flatMap(_.assetActivityRecords.filter(_.value.nonEmpty))
      eventsWithAssetRecords should not be empty withClue
        s"At least one event after cursor $cursorBeforeSend should carry non-empty assetActivityRecords"
      val assetPartyProto = assetParty.toProtoPrimitive
      val eventsNamingAssetParty = eventsWithAssetRecords.filter(_.value.exists { r =>
        r.party == assetPartyProto && r.weight > 0L
      })
      eventsNamingAssetParty should not be empty withClue
        s"At least one event should list $assetParty as an asset activity record party with weight > 0"
    }

    clue(
      s"Round $sendRoundNoFa negative case: non-FA submitter produces app records (bob as FA confirmer) but zero asset activity"
    ) {
      assertRoundTotalsOk(sendRoundNoFa) { t =>
        t.activityRecordsCount should be >= 1L withClue
          s"Round $sendRoundNoFa should have at least one activity record (bob is a FA confirmer on the splitwell→bob transfer)"
        t.totalAppActivityWeight should be > 0L withClue
          s"Round $sendRoundNoFa should have non-zero app activity weight (attributed to bob via computeForUserSubmitted)"
        t.totalAssetActivityWeight shouldBe 0L withClue
          s"Round $sendRoundNoFa must have zero asset activity: non-FA submitter means findFASubmitter returns None → computeForFASubmitted never runs → no asset-side split"
      }
    }

    clue("scan.getEventById surfaces submitting_app_provider per compute path") {
      // FA-submitted path (round 5): submitting_app_provider = Some(assetMoverParty).
      val faSubmittedEvent = postSendEvents
        .find(_.assetActivityRecords.exists(_.value.nonEmpty))
        .getOrElse(
          fail(s"Expected an FA-submitted event with asset records after $cursorBeforeSend")
        )
      val faUpdateId = faSubmittedEvent.verdict
        .map(_.updateId)
        .getOrElse(fail("FA-submitted event should carry a verdict with an updateId"))
      val faEvent = sv1ScanBackend
        .getEventById(faUpdateId, None)
        .getOrElse(fail(s"scan.getEventById($faUpdateId) returned None"))
      faEvent.submittingAppProvider.map(_.value) shouldBe Some(
        assetMoverParty.toProtoPrimitive
      ) withClue
        s"FA-submitted event $faUpdateId should report $assetMoverParty as submitting_app_provider"

      // User-submitted path (round 6): submitting_app_provider absent — the field
      // is only populated by computeForFASubmitted, not computeForUserSubmitted.
      val postNoFaEvents = sv1ScanBackend.getEventHistory(
        count = 1000,
        after = Some(cursorBeforeNoFaSend),
        encoding = CompactJson,
      )
      val userSubmittedEvent = postNoFaEvents
        .find(e =>
          e.appActivityRecords.exists(_.records.nonEmpty) && e.assetActivityRecords.isEmpty
        )
        .getOrElse(
          fail(
            s"Expected a user-submitted event with app records (and no asset records) after $cursorBeforeNoFaSend"
          )
        )
      val noFaUpdateId = userSubmittedEvent.verdict
        .map(_.updateId)
        .getOrElse(fail("User-submitted event should carry a verdict with an updateId"))
      val noFaEvent = sv1ScanBackend
        .getEventById(noFaUpdateId, None)
        .getOrElse(fail(s"scan.getEventById($noFaUpdateId) returned None"))
      noFaEvent.submittingAppProvider shouldBe None withClue
        s"User-submitted event $noFaUpdateId should have no submitting_app_provider"
    }

    // Capture the earlier round's asset:app ratio for the cap-crossing comparison below.
    val sendRoundTotals = inside(sv1ScanBackend.getRewardAccountingActivityTotals(sendRound)) {
      case GetRewardAccountingActivityTotalsResponse.members
            .RewardAccountingActivityTotalsOk(t) =>
        t
    }

    clue(
      "Lower maxExtraFeaturedAssetActivityWeightPerAsset mid-flow so the next asset-moving send crosses the per-asset cap"
    ) {
      updateTbarV11RewardConfig(maxExtraFeaturedAssetActivityWeightPerAsset = BigDecimal("0.05"))
    }

    // The cap-lower vote took effect at the current simtime; new rounds opening
    // after this point see the lowered cap in lookupAmuletConfigAsOf.
    for (round <- 9 to 11) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    val capCrossRound = 11L
    clue(
      s"CIP-104 cap enforcement: send in round $capCrossRound against the lowered per-asset cap (0.05)"
    ) {
      aliceWalletClient.transferPreapprovalSend(
        assetParty,
        BigDecimal(10),
        UUID.randomUUID().toString,
      )
    }
    for (round <- 12 to 13) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    clue(
      s"Round $capCrossRound asset ratio should be ~half of round $sendRound's (cap 0.05 vs 0.1)"
    ) {
      assertRoundTotalsOk(capCrossRound) { t =>
        t.totalAppActivityWeight should be > 0L
        t.totalAssetActivityWeight should be > 0L withClue
          s"Round $capCrossRound should still have asset activity (cap is lower, not zero)"
        // Baseline round saw asset ~0.11 × app. With cap halved from 0.1 to 0.05,
        // the per-asset share halves, so asset should land near ~0.055 × app.
        // Pin the halving relationship directly rather than tracking absolute values.
        val baselineRatioPct = sendRoundTotals.totalAssetActivityWeight * 100L /
          sendRoundTotals.totalAppActivityWeight
        val capCrossRatioPct = t.totalAssetActivityWeight * 100L / t.totalAppActivityWeight
        capCrossRatioPct should be < baselineRatioPct withClue
          s"Cap-lowered round ratio ($capCrossRatioPct%) should be smaller than baseline round $sendRound's ratio ($baselineRatioPct%)"
        capCrossRatioPct * 3L should be < baselineRatioPct * 2L withClue
          s"Cap-lowered round ratio ($capCrossRatioPct%) should be well under 2/3 of baseline ($baselineRatioPct%) — expect ~half"
      }
    }

    // Snapshot the cap-crossed round's totals so the weight-change round below
    // can compare against them (same cap of 0.05, but assetParty weight 1→3).
    val capCrossRoundTotals =
      inside(sv1ScanBackend.getRewardAccountingActivityTotals(capCrossRound)) {
        case GetRewardAccountingActivityTotalsResponse.members
              .RewardAccountingActivityTotalsOk(t) =>
          t
      }

    clue(s"Triple assetParty's FA activity weight so step-8 attribution scales up") {
      updateFeaturedAppRightWeight(assetParty, BigDecimal(3))
    }

    // Advance to let the new weight propagate into lookupFeaturedAppPartiesAsOf.
    for (round <- 14 to 16) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    val weightChangeRound = 16L
    clue(
      s"Send in round $weightChangeRound to measure step-8 weight application (weight=3, cap=0.05 unchanged)"
    ) {
      aliceWalletClient.transferPreapprovalSend(
        assetParty,
        BigDecimal(10),
        UUID.randomUUID().toString,
      )
    }
    for (round <- 17 to 18) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    clue(
      s"Round $weightChangeRound asset ratio should scale ~3× over round $capCrossRound's (weight 3 vs 1, cap unchanged)"
    ) {
      assertRoundTotalsOk(weightChangeRound) { t =>
        t.totalAppActivityWeight should be > 0L
        t.totalAssetActivityWeight should be > 0L
        // Step 8 of computeAssetActivity applies the FA weight AFTER cap clamp.
        // Weight 1→3 triples assetParty's capped share; DSO's share (weight 0.1,
        // re-attributed to the submitter) is unaffected. Net ratio climbs
        // from ~5.5% toward ~15.5%. Bounds pin "much larger than 2×, less than 4×".
        val capCrossRatioPct = capCrossRoundTotals.totalAssetActivityWeight * 100L /
          capCrossRoundTotals.totalAppActivityWeight
        val weightChangeRatioPct = t.totalAssetActivityWeight * 100L / t.totalAppActivityWeight
        weightChangeRatioPct should be > capCrossRatioPct * 2L withClue
          s"Weight-tripled round ratio ($weightChangeRatioPct%) should be at least 2× capCross ratio ($capCrossRatioPct%)"
        weightChangeRatioPct should be < capCrossRatioPct * 4L withClue
          s"Weight-tripled round ratio ($weightChangeRatioPct%) should be less than 4× capCross ratio ($capCrossRatioPct%) — DSO's share is weight-independent"
      }
    }
  }

  // Votes an AmuletConfig update that sets the three TBAR v1.1 RewardConfig
  // Optional fields. InitialRewardConfig leaves them None at bootstrap, so until
  // this runs computeForFASubmitted short-circuits (no asset activity records).
  // Defaults match the CIP-104 recommended values; override individual fields to
  // exercise different asset-side configurations (e.g. a lower per-asset cap).
  private def updateTbarV11RewardConfig(
      extraFeaturedAssetActivityWeight: BigDecimal = BigDecimal("0.2"),
      maxExtraFeaturedAssetActivityWeightPerAsset: BigDecimal = BigDecimal("0.1"),
      dsoAppActivityWeight: BigDecimal = BigDecimal("0.1"),
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val amuletRules = sv1Backend.getDsoInfo().amuletRules
    val existing = AmuletConfigSchedule(amuletRules).getConfigAsOf(env.environment.clock.now)
    val rc = existing.rewardConfig.get()
    val newRc = new RewardConfig(
      rc.mintingVersion,
      rc.dryRunVersion,
      rc.batchSize,
      rc.rewardCouponTimeToLive,
      rc.appRewardCouponThreshold,
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

  // Votes an UpdateFeaturedAppRight choice that re-grants the FA right with a new
  // activityWeight (appKind and opsParties are preserved by Daml's `this with ...`).
  // Blocks until scan observes the new weight.
  private def updateFeaturedAppRightWeight(
      party: PartyId,
      newWeight: BigDecimal,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val rightCid = clue(s"Look up featured app right for $party") {
      sv1ScanBackend.lookupFeaturedAppRight(party).value.contractId
    }
    val action = new ARC_DsoRules(
      new SRARC_UpdateFeaturedAppRight(
        new DsoRules_UpdateFeaturedAppRight(
          rightCid,
          new FeaturedAppRight_Update("updating activity weight", newWeight.bigDecimal),
        )
      )
    )
    votingFlow(action, effectivity = None, accept = true, expiration = Duration.ofSeconds(60))
    clue(s"Wait for $party featured app right weight to be $newWeight") {
      eventually() {
        val weight = sv1ScanBackend
          .lookupFeaturedAppRight(party)
          .value
          .payload
          .activityWeight
          .toScala
          .map(BigDecimal(_))
        weight.getOrElse(BigDecimal(1)).compare(newWeight) shouldBe 0 withClue
          s"activity weight for $party should be $newWeight"
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

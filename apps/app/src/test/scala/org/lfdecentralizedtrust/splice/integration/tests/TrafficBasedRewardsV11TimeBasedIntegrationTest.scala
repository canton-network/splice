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
    updateTbarV11RewardConfig()

    // Receivers' TransferPreapprovals so the sender submits the amulet transfer
    // directly — assetMoverParty becomes the Daml submitter, and findFASubmitter
    // attributes the transaction to the FAK_ASSETMOVING grant.
    bobWalletClient.createTransferPreapproval()
    charlieWalletClient.createTransferPreapproval()
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
    val baselineRound = 5L
    val cursorBeforeBaseline = eventuallySucceeds() { latestEventHistoryCursor(sv1ScanBackend) }
    clue(s"Round $baselineRound: FA alice→$assetParty + non-FA splitwell→$assetParty in parallel") {
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
      val faEvent = findFaSubmittedEvent(baselineEvents, assetParty)
      assertAssetRatioInEvent(
        event = faEvent,
        submitter = assetMoverParty,
        assetParty = assetParty,
        expectedPct = 10,
        tolerancePct = 3,
        label = s"baseline alice→$assetParty",
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
      val noFaEvent = sv1ScanBackend
        .getEventById(noFaUpdateId, None)
        .getOrElse(fail(s"scan.getEventById($noFaUpdateId) returned None"))
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
      val reloaded = sv1ScanBackend
        .getEventById(faUpdateId, None)
        .getOrElse(fail(s"scan.getEventById($faUpdateId) returned None"))
      reloaded.submittingAppProvider.map(_.value) shouldBe Some(
        assetMoverParty.toProtoPrimitive
      ) withClue
        s"FA-submitted event $faUpdateId should report $assetMoverParty as submitting_app_provider"
    }

    // Mid-flow: halve the per-asset cap so the next scenario round exercises both
    // cap clamping and step-8 weight scaling via two parallel sends.
    clue(
      "Lower maxExtraFeaturedAssetActivityWeightPerAsset mid-flow for the cap+weight scenario round"
    ) {
      updateTbarV11RewardConfig(maxExtraFeaturedAssetActivityWeightPerAsset = BigDecimal("0.05"))
    }
    for (round <- 8 to 10) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    // Round 10 scenario: both cap=0.05 cases in parallel.
    // - alice→assetParty (weight=1): exercises cap clamp (~5% per-record asset ratio).
    // - alice→highWeightAssetParty (weight=3): exercises step-8 weight application
    //   atop the same cap (~15% per-record asset ratio).
    val capWeightRound = 10L
    val cursorBeforeCapWeight = eventuallySucceeds() { latestEventHistoryCursor(sv1ScanBackend) }
    clue(
      s"Round $capWeightRound: cap-crossed alice→$assetParty (w=1) + weight-tripled alice→$highWeightAssetParty (w=3)"
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
    }
    for (round <- 11 to 12) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    val capWeightEvents = sv1ScanBackend.getEventHistory(
      count = 1000,
      after = Some(cursorBeforeCapWeight),
      encoding = CompactJson,
    )

    clue(
      s"Round $capWeightRound cap-crossed event (alice→$assetParty, w=1, cap=0.05): per-record asset ratio ~5%"
    ) {
      val event = findFaSubmittedEvent(capWeightEvents, assetParty)
      assertAssetRatioInEvent(
        event = event,
        submitter = assetMoverParty,
        assetParty = assetParty,
        expectedPct = 5,
        tolerancePct = 3,
        label = s"cap-crossed alice→$assetParty",
      )
    }

    clue(
      s"Round $capWeightRound weight-tripled event (alice→$highWeightAssetParty, w=3, cap=0.05): per-record asset ratio ~15%"
    ) {
      val event = findFaSubmittedEvent(capWeightEvents, highWeightAssetParty)
      assertAssetRatioInEvent(
        event = event,
        submitter = assetMoverParty,
        assetParty = highWeightAssetParty,
        expectedPct = 15,
        tolerancePct = 4,
        label = s"weight-tripled alice→$highWeightAssetParty",
      )
    }

    // Revoke highWeightAssetParty's FA grant mid-flow. Subsequent sends to
    // highWeightAssetParty should no longer attribute asset activity to them
    // (lookupFeaturedAppPartiesAsOf no longer surfaces the grant), while the
    // DSO's weighted share still flows through — re-attributed to the submitter.
    clue(s"Revoke $highWeightAssetParty's FA grant mid-flow") {
      revokeFeaturedAppRight(highWeightAssetParty)
    }
    for (round <- 13 to 15) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    val revokedRound = 15L
    val cursorBeforeRevokedSend = eventuallySucceeds() {
      latestEventHistoryCursor(sv1ScanBackend)
    }
    clue(
      s"Round $revokedRound: alice→$highWeightAssetParty after revocation should have no asset record for the revoked party"
    ) {
      aliceWalletClient.transferPreapprovalSend(
        highWeightAssetParty,
        BigDecimal(10),
        UUID.randomUUID().toString,
      )
    }
    for (round <- 16 to 17) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    clue(
      s"Round $revokedRound: assetActivityRecords omits revoked $highWeightAssetParty; DSO share still re-attributes to $assetMoverParty"
    ) {
      val revokedEvents = sv1ScanBackend.getEventHistory(
        count = 1000,
        after = Some(cursorBeforeRevokedSend),
        encoding = CompactJson,
      )
      // Find an FA-submitted event whose assetActivityRecords includes the
      // submitter (= DSO share re-attribution). This is the alice→highWeightAssetParty
      // transfer post-revocation; without a FAK_ASSET confirmer, only the DSO's
      // share flows through, and it's re-attributed to the submitting app.
      val revokedEvent = findFaSubmittedEvent(revokedEvents, assetMoverParty)
      val assetRecords = revokedEvent.assetActivityRecords
        .getOrElse(fail("revoked event should still carry DSO-share asset records"))
        .value
      val assetPartyProtos = assetRecords.map(_.party).toSet
      assetPartyProtos should not contain highWeightAssetParty.toProtoPrimitive withClue
        s"$highWeightAssetParty was revoked; its FA grant should no longer attribute asset activity"
      assetPartyProtos should contain(assetMoverParty.toProtoPrimitive) withClue
        s"DSO's share should still re-attribute to the submitting app $assetMoverParty"
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

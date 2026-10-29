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
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_GrantFeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_GrantFeaturedAppRight
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
    // Reusing the alice/bob wallet clients avoids the UNKNOWN_SUBMITTERS race
    // that onboardUser hits when it tries to submit from a freshly-allocated
    // party before topology propagation.
    val assetMoverParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val assetParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

    val assetMoverValidatorParty = aliceValidatorBackend.getValidatorPartyId()
    val assetValidatorParty = bobValidatorBackend.getValidatorPartyId()
    clue(s"Grant FAK_AssetMoving right to $assetMoverParty") {
      grantFeaturedAppRightWithKind(
        assetMoverParty,
        FeaturedAppKind.FAK_ASSETMOVING,
        opsParties = Seq(assetMoverValidatorParty),
      )
    }
    clue(s"Grant FAK_Asset right to $assetParty") {
      grantFeaturedAppRightWithKind(
        assetParty,
        FeaturedAppKind.FAK_ASSET,
        opsParties = Seq(assetValidatorParty),
      )
    }
    enableTbarV11AssetSide()

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
    // Pin the event-stream cursor just before the send so the post-send query
    // below returns only events generated by the send and the subsequent round
    // closes — avoids pagination issues at the ~thousands-of-events global history.
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

    clue(s"Round $sendRound shows asset-side TBAR v1.1 activity from the asset-moving send") {
      eventually(timeUntilSuccess = 30.seconds) {
        inside(sv1ScanBackend.getRewardAccountingActivityTotals(sendRound)) {
          case GetRewardAccountingActivityTotalsResponse.members
                .RewardAccountingActivityTotalsOk(t) =>
            t.activityRecordsCount should be >= 1L withClue
              s"Round $sendRound should have at least one activity record from the send"
            t.totalAppActivityWeight should be > 0L withClue
              s"Round $sendRound should have non-zero app activity weight (total burn attributed to submitter)"
            // Asset-side split per CIP-104: with extraFeaturedAssetActivityWeight=0.2,
            // maxExtraFeaturedAssetActivityWeightPerAsset=0.1, dsoAppActivityWeight=0.1,
            // and a single FAK_ASSET confirmer (assetParty) plus the DSO as asset
            // confirmers, the asset total runs roughly 5–25% of totalAppActivityWeight
            // (envelope split, caps, and dso-weight application eat into the headline
            // 0.2 share). Tight bounds catch both the "0 asset activity" regression
            // and any accidental >1x inflation without over-fitting the exact value.
            val assetLower = (BigDecimal(t.totalAppActivityWeight) * 0.05).toLong
            val assetUpper = (BigDecimal(t.totalAppActivityWeight) * 0.25).toLong
            t.totalAssetActivityWeight should be >= assetLower withClue
              s"Round $sendRound asset activity weight (${t.totalAssetActivityWeight}) should be at least 5% of app activity weight (${t.totalAppActivityWeight})"
            t.totalAssetActivityWeight should be <= assetUpper withClue
              s"Round $sendRound asset activity weight (${t.totalAssetActivityWeight}) should be at most 25% of app activity weight (${t.totalAppActivityWeight})"
        }
      }
    }

    clue(s"At least one event exposes assetParty $assetParty via per-event assetActivityRecords") {
      // Hits the Shape-1 field directly (OmitNullArray-wrapped array), confirming
      // the event-api PR's new field is populated end-to-end — not only the aggregate.
      // Scan returns events in ascending-time order, so with None cursor we would
      // hit the oldest events first; cursorBeforeSend narrows to the post-send
      // window that actually carries asset activity.
      val postSendEvents = sv1ScanBackend.getEventHistory(
        count = 1000,
        after = Some(cursorBeforeSend),
        encoding = CompactJson,
      )
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
      eventually(timeUntilSuccess = 30.seconds) {
        inside(sv1ScanBackend.getRewardAccountingActivityTotals(sendRoundNoFa)) {
          case GetRewardAccountingActivityTotalsResponse.members
                .RewardAccountingActivityTotalsOk(t) =>
            t.activityRecordsCount should be >= 1L withClue
              s"Round $sendRoundNoFa should have at least one activity record (bob is a FA confirmer on the splitwell→bob transfer)"
            t.totalAppActivityWeight should be > 0L withClue
              s"Round $sendRoundNoFa should have non-zero app activity weight (attributed to bob via computeForUserSubmitted)"
            t.totalAssetActivityWeight shouldBe 0L withClue
              s"Round $sendRoundNoFa must have zero asset activity: non-FA submitter means findFASubmitter returns None → computeForFASubmitted never runs → no asset-side split"
        }
      }
    }
  }

  // Flips on asset-side computation by voting a RewardConfig update that
  // populates the three TBAR v1.1 Optional fields. The InitialRewardConfig
  // bootstrap leaves them None, so until this runs computeForFASubmitted
  // short-circuits and no asset activity records are produced.
  private def enableTbarV11AssetSide()(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val amuletRules = sv1Backend.getDsoInfo().amuletRules
    val existing = AmuletConfigSchedule(amuletRules).getConfigAsOf(env.environment.clock.now)
    val rc = existing.rewardConfig.get()
    val newRc = new RewardConfig(
      rc.mintingVersion,
      rc.dryRunVersion,
      rc.batchSize,
      rc.rewardCouponTimeToLive,
      rc.appRewardCouponThreshold,
      Optional.of(BigDecimal("0.2").bigDecimal),
      Optional.of(BigDecimal("0.1").bigDecimal),
      Optional.of(BigDecimal("0.1").bigDecimal),
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

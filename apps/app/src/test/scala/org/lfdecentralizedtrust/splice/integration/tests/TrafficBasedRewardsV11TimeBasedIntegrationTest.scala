package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.console.ScanAppBackendReference
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import org.lfdecentralizedtrust.splice.sv.config.InitialRewardConfig
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.GetRewardAccountingActivityTotalsResponse
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment

import scala.concurrent.duration.*

// Sibling to TrafficBasedRewardsTimeBasedIntegrationTest covering TBAR v1.1
// (asset-side activity introduced by the CIP-104 amendment). The pre-amendment
// test is kept as a regression reference per the author's and reviewer's
// 2026-10-28 agreement on #7837.
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
abstract class TrafficBasedRewardsV11TimeBasedIntegrationTestBase
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with TimeTestUtil
    with AmuletConfigUtil
    with ExternallySignedPartyTestUtil
    with TokenStandardTest {

  protected def rewardConfigMode: TrafficBasedRewardsV11TimeBasedIntegrationTestBase.RewardConfigMode

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

    for (round <- 1 to 3) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }

    clue(
      "Bootstrap rounds 0..2 have zero activity (no featured apps yet); TBAR v1.1 asset-side totals are also zero under defaults"
    ) {
      assertZeroTotals(sv1ScanBackend, 0L to 2L)
    }
  }

  // Asserts both the pre-amendment totals (app-side + minting allowance) and the
  // TBAR v1.1 asset-side totals (asset activity weight + per-source rewards +
  // featured-app/asset issuance rates) are all zero for the given rounds.
  private def assertZeroTotals(
      scan: ScanAppBackendReference,
      rounds: Seq[Long],
      timeout: FiniteDuration = 20.seconds,
  ): Unit =
    rounds.foreach { round =>
      eventually(timeout) {
        inside(scan.getRewardAccountingActivityTotals(round)) {
          case GetRewardAccountingActivityTotalsResponse.members
                .RewardAccountingActivityTotalsOk(t) =>
            t.activityRecordsCount shouldBe 0L withClue
              s"Round $round should have no activity records on ${scan.name}"
            t.totalAppActivityWeight shouldBe 0L withClue
              s"Round $round should have no activity weight on ${scan.name}"
            t.totalAssetActivityWeight shouldBe 0L withClue
              s"Round $round should have no asset activity weight on ${scan.name}"
            BigDecimal(t.totalAppRewardMintingAllowance) shouldBe BigDecimal(0) withClue
              s"Round $round should have no minting allowance on ${scan.name}"
            BigDecimal(t.totalAppRewardFromAppActivity) shouldBe BigDecimal(0) withClue
              s"Round $round should have no app-side reward on ${scan.name}"
            BigDecimal(t.totalAppRewardFromAssetActivity) shouldBe BigDecimal(0) withClue
              s"Round $round should have no asset-side reward on ${scan.name}"
            BigDecimal(t.issuancePerFeaturedAppActivity) shouldBe BigDecimal(0) withClue
              s"Round $round should have no featured-app issuance on ${scan.name}"
            BigDecimal(t.issuancePerFeaturedAssetActivity) shouldBe BigDecimal(0) withClue
              s"Round $round should have no featured-asset issuance on ${scan.name}"
        }
      }
    }

  private def assertOldestOpenRound(
      expectedOldestRound: Long
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue(s"Asserting oldest open round=$expectedOldestRound") {
      eventually() {
        sv1ScanBackend.getOpenAndIssuingMiningRounds()._1
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

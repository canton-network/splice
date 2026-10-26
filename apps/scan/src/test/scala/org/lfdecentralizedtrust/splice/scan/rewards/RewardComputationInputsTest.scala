// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.BaseTest
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.RewardConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.RewardVersion.REWARDVERSION_TRAFFICBASEDAPPREWARDS
import org.lfdecentralizedtrust.splice.codegen.java.splice.issuance.IssuanceConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.util.{BigDecimalMatchers, SpliceUtil}
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional

class RewardComputationInputsTest extends AnyWordSpec with BaseTest with BigDecimalMatchers {

  import RewardComputationInputsTest.*

  "RewardComputationInputs.fromOpenMiningRound" should {

    "extract all fields from an OpenMiningRound with CIP-104 config" in {
      RewardComputationInputs.fromOpenMiningRound(mainNetRound) shouldBe Some((mainNet, 100))
    }

    "use default developmentFundPercentage when optDevelopmentFundPercentage is None" in {
      val round = mkOpenMiningRound(optDevelopmentFundPercentage = Optional.empty())
      RewardComputationInputs.fromOpenMiningRound(round).map(_._1) shouldBe Some(mainNet)
    }

    "return None when rewardConfig is None" in {
      val round = mkOpenMiningRound(rewardConfig = Optional.empty())
      RewardComputationInputs.fromOpenMiningRound(round) shouldBe None
    }

    "return None when trafficPrice is None" in {
      val round = mkOpenMiningRound(trafficPrice = Optional.empty())
      RewardComputationInputs.fromOpenMiningRound(round) shouldBe None
    }
  }

  "RewardComputationInputs.deriveIssuanceParams" should {

    cases.foreach { tc =>
      tc.label in {
        val result =
          tc.inputs.deriveIssuanceParams(
            tc.totalRoundAppActivityWeight,
            tc.totalFeaturedAssetWeight,
          )
        result.appMintingAllowanceCC should beEqualUpTo(
          tc.expected.appMintingAllowanceCC,
          10,
        )
        result.assetMintingAllowanceCC should beEqualUpTo(
          tc.expected.assetMintingAllowanceCC,
          10,
        )
        result.unclaimedMintingAllowanceCC should beEqualUpTo(
          tc.expected.unclaimedMintingAllowanceCC,
          10,
        )
        result.issuancePerAppBurnCC should beEqualUpTo(
          tc.expected.issuancePerAppBurnCC,
          10,
        )
        result.issuancePerAppBurnCCPerMB should beEqualUpTo(
          tc.expected.issuancePerAppBurnCCPerMB,
          10,
        )
        result.issuancePerAssetBurnCC should beEqualUpTo(
          tc.expected.issuancePerAssetBurnCC,
          10,
        )
        result.issuancePerAssetBurnCCPerMB should beEqualUpTo(
          tc.expected.issuancePerAssetBurnCCPerMB,
          10,
        )
        result.appRewardCouponThresholdCC should beEqualUpTo(
          tc.expected.appRewardCouponThresholdCC,
          10,
        )
      }
    }
  }
}

object RewardComputationInputsTest {

  import RewardComputationInputs.{fromBigDecimal as n}

  private val defaultRewardConfig = new RewardConfig(
    REWARDVERSION_TRAFFICBASEDAPPREWARDS,
    Optional.empty(),
    100L,
    new RelTime(36L * 3600L * 1000000L),
    SpliceUtil.damlDecimal(0.5),
    Optional.empty(),
    Optional.empty(),
    Optional.empty(),
  )

  private def mkOpenMiningRound(
      trafficPrice: Optional[java.math.BigDecimal] = Optional.of(SpliceUtil.damlDecimal(60.0)),
      rewardConfig: Optional[RewardConfig] = Optional.of(defaultRewardConfig),
      optDevelopmentFundPercentage: Optional[java.math.BigDecimal] =
        Optional.of(SpliceUtil.damlDecimal(0.05)),
  ): OpenMiningRound = {
    val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
    new OpenMiningRound(
      "dso-party",
      new Round(42L),
      SpliceUtil.damlDecimal(0.14877),
      now,
      now.plusSeconds(600),
      new RelTime(600L * 1000000L),
      SpliceUtil.defaultTransferConfig(10, SpliceUtil.damlDecimal(1.0)),
      new IssuanceConfig(
        SpliceUtil.damlDecimal(10000000000.0),
        SpliceUtil.damlDecimal(0.0),
        SpliceUtil.damlDecimal(0.62),
        SpliceUtil.damlDecimal(0.0),
        SpliceUtil.damlDecimal(1.5),
        SpliceUtil.damlDecimal(0.6),
        Optional.empty(),
        optDevelopmentFundPercentage,
      ),
      new RelTime(600L * 1000000L),
      trafficPrice,
      rewardConfig,
    )
  }

  private val mainNetRound: OpenMiningRound = mkOpenMiningRound()

  // 600s tick → 52560 rounds/year
  private val tickDurationMicros: Long = 600L * 1000000L
  private val microsPerYear: Long = 365L * 24 * 3600 * 1000000L
  private val roundsPerYear: BigDecimal = BigDecimal(microsPerYear) / BigDecimal(tickDurationMicros)

  /** MainNet-calibrated inputs from live DSO config (2026-03-31, round 89682).
    *
    * Source: https://docs.global.canton.network.sync.global/dso
    *   amuletToIssuePerYear = 10e9, appRewardPercentage = 0.62
    *   featuredAppRewardCap = 1.5
    *   optDevelopmentFundPercentage = None → default 0.05
    *   trafficPrice = 60 $/MB, amuletPrice = 0.14877 $/CC
    *
    * Derived: amuletsToIssueInRound_beforeDevFund ≈ 190258.75
    *          amuletsToIssueInRound ≈ 180745.81 (after 5% devfund)
    *          rewardsToIssue ≈ 112062.40, trafficPriceInCCPerMB ≈ 403.30, appRewardCouponThresholdCC ≈ 3.36
    */
  val mainNet = RewardComputationInputs(
    amuletToIssuePerYear = n(BigDecimal("10000000000")),
    appRewardPercentage = n(BigDecimal("0.62")),
    featuredAppRewardCap = n(BigDecimal("1.5")),
    developmentFundPercentage = n(BigDecimal("0.05")),
    tickDurationMicros = tickDurationMicros,
    amuletPrice = n(BigDecimal("0.14877")),
    trafficPrice = n(BigDecimal("60")),
    appRewardCouponThreshold = n(BigDecimal("0.5")),
  )

  /** Baseline inputs: simple round numbers for easy manual verification.
    *
    * amuletToIssuePerYear = 52560 → amuletsToIssueInRound_beforeDevFund = 1.0
    * developmentFundPercentage = 0.1 → amuletsToIssueInRound = 0.9
    * appRewardPercentage = 0.5 → rewardsToIssue = 0.45
    * amuletPrice = 1.0, trafficPrice = 1.0 → trafficPriceInCCPerMB = 1.0
    * featuredAppRewardCap = 100 (high, so cap doesn't bind)
    * appRewardCouponThreshold = 0.5 → appRewardCouponThresholdCC = 0.5
    */
  private val simpleArithmetic = RewardComputationInputs(
    amuletToIssuePerYear = n(roundsPerYear), // yields exactly 1.0 per round
    appRewardPercentage = n(BigDecimal("0.5")),
    featuredAppRewardCap = n(BigDecimal("100")),
    developmentFundPercentage = n(BigDecimal("0.1")),
    tickDurationMicros = tickDurationMicros,
    amuletPrice = n(BigDecimal("1.0")),
    trafficPrice = n(BigDecimal("1.0")),
    appRewardCouponThreshold = n(BigDecimal("0.5")),
  )

  /** Inputs from the amendment's "Example: Tiered App Reward Distribution".
    *
    *   roundMintingAllowanceCC = 112,000 CC per round
    *   featuredAppRewardCap = 0.79
    *   trafficPrice = $60/MB, amuletPrice = $0.1/CC → trafficPriceInCCPerMB = 600 CC/MB
    *
    * Set amuletToIssuePerYear so amuletsToIssueInRound = 112,000, with
    * appRewardPercentage = 1 and developmentFundPercentage = 0 to keep the
    * minting allowance identity transparent.
    */
  private val dvpExample = RewardComputationInputs(
    amuletToIssuePerYear = n(roundsPerYear * BigDecimal("112000")),
    appRewardPercentage = n(BigDecimal("1")),
    featuredAppRewardCap = n(BigDecimal("0.79")),
    developmentFundPercentage = n(BigDecimal("0")),
    tickDurationMicros = tickDurationMicros,
    amuletPrice = n(BigDecimal("0.1")),
    trafficPrice = n(BigDecimal("60")),
    appRewardCouponThreshold = n(BigDecimal("0")),
  )

  case class TestCase(
      label: String,
      inputs: RewardComputationInputs,
      totalRoundAppActivityWeight: Long,
      expected: RewardIssuanceParams,
      totalFeaturedAssetWeight: Long = 0L,
  )

  // Test cases are grouped in pairs: MainNet-calibrated first (realistic values
  // matching cantonscan.com), then a simple-arithmetic equivalent (round numbers
  // for easy manual verification of the same concept).

  val cases: Seq[TestCase] = Seq(
    // --- 1. Current MainNet traffic, cap binds ---

    // MainNet: BME = 0.8 (~95 MB traffic). With cap = 1.5, cap binds because
    // issuancePerCoupon = 112062.40 / 38340.89 ≈ 2.92 > 1.5.
    // ≈ 605 CC/MB ≈ $90/MB in rewards — within expected $60-90/MB range
    TestCase(
      label = "MainNet: BME=0.8, cap binds, unclaimed rewards",
      inputs = mainNet,
      totalRoundAppActivityWeight = 95129376L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("57549.5317604300"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal("54512.8731101940"),
        issuancePerAppBurnCC = BigDecimal("1.5"),
        issuancePerAppBurnCCPerMB = BigDecimal("604.9606775559"),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("3.3608926531"),
      ),
    ),
    // Simple: 1 MB traffic, no cap binding
    // issuancePerCoupon = 0.45/1.0 = 0.45, unclaimed = 0
    TestCase(
      label = "simple: 1 MB traffic, no cap binding",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 1000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0.45"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal("0"),
        issuancePerAppBurnCC = BigDecimal("0.45"),
        issuancePerAppBurnCCPerMB = BigDecimal("0.45"),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),

    // --- 2. More traffic dilutes issuance per MB ---

    // MainNet: doubled traffic price $120/MB → enough coupons that cap no longer binds.
    // trafficPriceInCCPerMB = 120/0.14877 ≈ 806.60, totalCoupons ≈ 76681.78
    // issuancePerCoupon = 112062.40 / 76681.78 ≈ 1.46 < 1.5 (cap does not bind)
    TestCase(
      label = "MainNet: doubled traffic price $120/MB, cap does not bind",
      inputs = mainNet.copy(trafficPrice = n(BigDecimal("120"))),
      totalRoundAppActivityWeight = 95129376L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("112062.4048706240"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal("0E-10"),
        issuancePerAppBurnCC = BigDecimal("1.4604254993"),
        issuancePerAppBurnCCPerMB = BigDecimal("1177.9999994354"),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("3.3608926531"),
      ),
    ),
    // Simple: double traffic (2 MB) → half the issuance per featured app activity
    // issuancePerCoupon = 0.45/2.0 = 0.225 (half of 0.45 from 1 MB case)
    TestCase(
      label = "simple: double traffic halves issuance per featured app activity",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 2000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0.45"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal("0"),
        issuancePerAppBurnCC = BigDecimal("0.225"),
        issuancePerAppBurnCCPerMB = BigDecimal("0.225"),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),
    // Simple: 10 MB traffic dilutes issuance per MB
    // issuancePerCoupon = 0.45/10.0 = 0.045
    TestCase(
      label = "simple: more traffic dilutes issuance per MB",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 10000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0.45"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal("0"),
        issuancePerAppBurnCC = BigDecimal("0.045"),
        issuancePerAppBurnCCPerMB = BigDecimal("0.045"),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),

    // --- 3. Cap binds with very low traffic ---

    // MainNet: 0.5 MB traffic, cap binds even more strongly.
    // totalCoupons_CC = 0.5 * 403.30 ≈ 201.65
    // issuancePerCoupon = min(112062.40/201.65, 1.5) = 1.5 (cap binds)
    // unclaimed = 112062.40 - 1.5 * 201.65 ≈ 111759.92
    TestCase(
      label = "MainNet: very low traffic, cap binds, mostly unclaimed",
      inputs = mainNet,
      totalRoundAppActivityWeight = 500000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("302.4803387780"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal("111759.9245318460"),
        issuancePerAppBurnCC = BigDecimal("1.5"),
        issuancePerAppBurnCCPerMB = BigDecimal("604.9606775559"),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("3.3608926531"),
      ),
    ),
    // Simple: cap = 0.1, 1 MB → cap binds, unclaimed = 0.35
    TestCase(
      label = "simple: cap binds, producing unclaimed rewards",
      inputs = simpleArithmetic.copy(featuredAppRewardCap = n(BigDecimal("0.1"))),
      totalRoundAppActivityWeight = 1000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0.1"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal("0.35"),
        issuancePerAppBurnCC = BigDecimal("0.1"),
        issuancePerAppBurnCCPerMB = BigDecimal("0.1"),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),

    // --- 4. Cap binds with higher total traffic ---

    // Simple: cap = 0.2, 2 MB → cap binds, unclaimed = 0.05
    TestCase(
      label = "simple: lower cap with 2 MB total traffic produces unclaimed rewards",
      inputs = simpleArithmetic.copy(featuredAppRewardCap = n(BigDecimal("0.2"))),
      totalRoundAppActivityWeight = 2000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0.4"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal("0.05"),
        issuancePerAppBurnCC = BigDecimal("0.2"),
        issuancePerAppBurnCCPerMB = BigDecimal("0.2"),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),

    // --- 5. Coin price affects threshold and conversion ---

    // MainNet: higher coin price $1.00/CC
    // trafficPriceInCCPerMB = 60/1.0 = 60, appRewardCouponThresholdCC = 0.5/1.0 = 0.5
    // totalCoupons = 95.13 * 60 = 5707.76, issuancePerCoupon = 112062.40/5707.76 ≈ 19.63
    // cap = 1.5 binds → issuancePerCoupon = 1.5
    TestCase(
      label = "MainNet: higher coin price $1.00/CC",
      inputs = mainNet.copy(amuletPrice = n(BigDecimal("1.00"))),
      totalRoundAppActivityWeight = 95129376L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("8561.6438400000"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal("103500.7610306240"),
        issuancePerAppBurnCC = BigDecimal("1.5"),
        issuancePerAppBurnCCPerMB = BigDecimal("90"),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),
    // Simple: amuletPrice = 2.0 → trafficPriceInCCPerMB = 0.5, appRewardCouponThresholdCC = 0.25
    TestCase(
      label = "simple: amuletPrice affects threshold and traffic conversion",
      inputs = simpleArithmetic.copy(amuletPrice = n(BigDecimal("2.0"))),
      totalRoundAppActivityWeight = 1000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0.45"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal("0"),
        issuancePerAppBurnCC = BigDecimal("0.9"),
        issuancePerAppBurnCCPerMB = BigDecimal("0.45"),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("0.25"),
      ),
    ),

    // --- 6. Zero traffic: all rewards unclaimed ---

    // MainNet: zero app burn → rate 0 per amendment lines 246-247.
    TestCase(
      label = "MainNet: zero traffic, all unclaimed",
      inputs = mainNet,
      totalRoundAppActivityWeight = 0L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal("112062.4048706240"),
        issuancePerAppBurnCC = BigDecimal(0),
        issuancePerAppBurnCCPerMB = BigDecimal(0),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("3.3608926531"),
      ),
    ),
    // Simple: zero app burn → rate 0 per amendment lines 246-247.
    TestCase(
      label = "simple: zero traffic, all rewards unclaimed",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 0L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal("0.45"),
        issuancePerAppBurnCC = BigDecimal(0),
        issuancePerAppBurnCCPerMB = BigDecimal(0),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),

    // --- 7. TBAR v1.1 asset side ---

    // 7a. Zero burn → zero-guard
    TestCase(
      label = "MainNet+asset: zero burn exercises zero-guard",
      inputs = mainNet,
      totalRoundAppActivityWeight = 95129376L,
      totalFeaturedAssetWeight = 0L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("57549.5317604300"),
        assetMintingAllowanceCC = BigDecimal("0"),
        unclaimedMintingAllowanceCC = BigDecimal("54512.8731101940"),
        issuancePerAppBurnCC = BigDecimal("1.5"),
        issuancePerAppBurnCCPerMB = BigDecimal("604.9606775559"),
        issuancePerAssetBurnCC = BigDecimal("0"),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("3.3608926531"),
      ),
    ),
    // Simple: cap doesn't bind, exact zero budget.
    TestCase(
      label = "simple+asset: zero burn exercises zero-guard",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 1000000L,
      totalFeaturedAssetWeight = 0L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0.45"),
        assetMintingAllowanceCC = BigDecimal("0"),
        unclaimedMintingAllowanceCC = BigDecimal("0"),
        issuancePerAppBurnCC = BigDecimal("0.45"),
        issuancePerAppBurnCCPerMB = BigDecimal("0.45"),
        issuancePerAssetBurnCC = BigDecimal("0"),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),

    // 7b. Burn exceeds leftover → asset prorated

    // MainNet: burn=100000 » leftover ≈ 54512.87 → rate ≈ 0.5451.
    TestCase(
      label = "MainNet+asset: burn exceeds leftover, asset prorated",
      inputs = mainNet,
      totalRoundAppActivityWeight = 95129376L,
      totalFeaturedAssetWeight = 250000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("57549.5317604300"),
        assetMintingAllowanceCC = BigDecimal("54512.8731101940"),
        unclaimedMintingAllowanceCC = BigDecimal("0"),
        issuancePerAppBurnCC = BigDecimal("1.5"),
        issuancePerAppBurnCCPerMB = BigDecimal("604.9606775559"),
        issuancePerAssetBurnCC = BigDecimal("0.5406586755"),
        issuancePerAssetBurnCCPerMB = BigDecimal("218.0514924380"),
        appRewardCouponThresholdCC = BigDecimal("3.3608926531"),
      ),
    ),
    // Simple: cap=0.1 binds; burn=4 CC, cap×burn=0.4 » leftover=0.35 → rate = 0.35/4 = 0.0875.
    TestCase(
      label = "simple+asset: burn exceeds leftover, asset prorated",
      inputs = simpleArithmetic.copy(featuredAppRewardCap = n(BigDecimal("0.1"))),
      totalRoundAppActivityWeight = 1000000L,
      totalFeaturedAssetWeight = 4000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0.1"),
        assetMintingAllowanceCC = BigDecimal("0.35"),
        unclaimedMintingAllowanceCC = BigDecimal("0"),
        issuancePerAppBurnCC = BigDecimal("0.1"),
        issuancePerAppBurnCCPerMB = BigDecimal("0.1"),
        issuancePerAssetBurnCC = BigDecimal("0.0875"),
        issuancePerAssetBurnCCPerMB = BigDecimal("0.0875"),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),

    // 7c. Cap not binding → asset arm vanishes

    // MainNet: doubled traffic $120/MB — cap doesn't bind, asset arm vanishes.
    TestCase(
      label = "MainNet+asset: cap not binding, asset arm vanishes",
      inputs = mainNet.copy(trafficPrice = n(BigDecimal("120"))),
      totalRoundAppActivityWeight = 95129376L,
      totalFeaturedAssetWeight = 1000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("112062.4048706240"),
        assetMintingAllowanceCC = BigDecimal("0"),
        unclaimedMintingAllowanceCC = BigDecimal("0E-10"),
        issuancePerAppBurnCC = BigDecimal("1.4604254993"),
        issuancePerAppBurnCCPerMB = BigDecimal("1177.9999994354"),
        issuancePerAssetBurnCC = BigDecimal("0"),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("3.3608926531"),
      ),
    ),
    // Simple: cap=100 doesn't bind; assetBudget = 0.
    TestCase(
      label = "simple+asset: cap not binding, asset arm vanishes",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 1000000L,
      totalFeaturedAssetWeight = 500000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0.45"),
        assetMintingAllowanceCC = BigDecimal("0"),
        unclaimedMintingAllowanceCC = BigDecimal("0"),
        issuancePerAppBurnCC = BigDecimal("0.45"),
        issuancePerAppBurnCCPerMB = BigDecimal("0.45"),
        issuancePerAssetBurnCC = BigDecimal("0"),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),

    // 7d. Non-terminating issuance at simple magnitudes
    // 7 MB → 45/700 = 0.0642857143 (non-terminating in scale-10); the allowance
    // equals rewardsToIssue exactly.
    TestCase(
      label = "simple+asset: non-terminating issuance (7 MB, cap not binding)",
      inputs = simpleArithmetic,
      totalRoundAppActivityWeight = 7000000L,
      totalFeaturedAssetWeight = 100000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0.45"),
        assetMintingAllowanceCC = BigDecimal("0"),
        unclaimedMintingAllowanceCC = BigDecimal("0"),
        issuancePerAppBurnCC = BigDecimal("0.0642857143"),
        issuancePerAppBurnCCPerMB = BigDecimal("0.0642857143"),
        issuancePerAssetBurnCC = BigDecimal("0"),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),

    // 7e. Cap binds + asset burn fits within leftover

    // MainNet: cap=1.5 × 4033.07 CC burn = 6049.60 fits within 54512.87 leftover.
    TestCase(
      label = "MainNet+asset: cap binds, asset fits within leftover",
      inputs = mainNet,
      totalRoundAppActivityWeight = 95129376L,
      totalFeaturedAssetWeight = 10000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("57549.5317604300"),
        assetMintingAllowanceCC = BigDecimal("6049.6067755590"),
        unclaimedMintingAllowanceCC = BigDecimal("48463.2663346350"),
        issuancePerAppBurnCC = BigDecimal("1.5"),
        issuancePerAppBurnCCPerMB = BigDecimal("604.9606775559"),
        issuancePerAssetBurnCC = BigDecimal("1.5"),
        issuancePerAssetBurnCCPerMB = BigDecimal("604.9606775559"),
        appRewardCouponThresholdCC = BigDecimal("3.3608926531"),
      ),
    ),
    // Simple: cap=0.1 × 0.1 CC burn = 0.01 fits within 0.35 leftover.
    TestCase(
      label = "simple+asset: cap binds, asset fits within leftover",
      inputs = simpleArithmetic.copy(featuredAppRewardCap = n(BigDecimal("0.1"))),
      totalRoundAppActivityWeight = 1000000L,
      totalFeaturedAssetWeight = 100000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0.1"),
        assetMintingAllowanceCC = BigDecimal("0.01"),
        unclaimedMintingAllowanceCC = BigDecimal("0.34"),
        issuancePerAppBurnCC = BigDecimal("0.1"),
        issuancePerAppBurnCCPerMB = BigDecimal("0.1"),
        issuancePerAssetBurnCC = BigDecimal("0.1"),
        issuancePerAssetBurnCCPerMB = BigDecimal("0.1"),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),

    // --- 7f. Zero app burn: asset tranche sees the full round budget ---

    // MainNet: zero app burn; asset tranche's rewardsToIssue == roundMintingAllowanceCC.
    // cap=1.5 × 4033.07 CC burn = 6049.60 fits within 112062.40 budget.
    TestCase(
      label = "MainNet+asset: zero app burn, asset gets full round budget",
      inputs = mainNet,
      totalRoundAppActivityWeight = 0L,
      totalFeaturedAssetWeight = 10000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0"),
        assetMintingAllowanceCC = BigDecimal("6049.6067755590"),
        unclaimedMintingAllowanceCC = BigDecimal("106012.7980950650"),
        issuancePerAppBurnCC = BigDecimal(0),
        issuancePerAppBurnCCPerMB = BigDecimal(0),
        issuancePerAssetBurnCC = BigDecimal("1.5"),
        issuancePerAssetBurnCCPerMB = BigDecimal("604.9606775559"),
        appRewardCouponThresholdCC = BigDecimal("3.3608926531"),
      ),
    ),
    // Simple: zero app burn; asset tranche's rewardsToIssue == roundMintingAllowanceCC = 0.45.
    // cap=0.1 × 4 CC burn = 0.4 fits within 0.45 budget.
    TestCase(
      label = "simple+asset: zero app burn, asset gets full round budget",
      inputs = simpleArithmetic.copy(featuredAppRewardCap = n(BigDecimal("0.1"))),
      totalRoundAppActivityWeight = 0L,
      totalFeaturedAssetWeight = 4000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("0"),
        assetMintingAllowanceCC = BigDecimal("0.4"),
        unclaimedMintingAllowanceCC = BigDecimal("0.05"),
        issuancePerAppBurnCC = BigDecimal(0),
        issuancePerAppBurnCCPerMB = BigDecimal(0),
        issuancePerAssetBurnCC = BigDecimal("0.1"),
        issuancePerAssetBurnCCPerMB = BigDecimal("0.1"),
        appRewardCouponThresholdCC = BigDecimal("0.5"),
      ),
    ),

    // --- 7g. Amendment "Tiered App Reward Distribution" DvP scenarios ---
    // Each DvP contributes 25 kB app activity to the submitting asset mover
    // and 2.5 kB asset activity for each of Bank1 and Bank2. Expected
    // per-DvP rewards from the amendment table:
    //   1k:  asset mover 11.85 CC, each bank 1.185 CC per DvP
    //   8k:  asset mover 11.85 CC, each bank 1.075 CC per DvP
    //   20k: asset mover  5.60 CC, each bank      0 CC per DvP

    // 1,000 DvPs → 25 MB app burn (15,000 CC), 5 MB asset burn (3,000 CC).
    // Cap binds on both tranches; room to spare.
    TestCase(
      label = "dvpExample: 1k DvPs, full app and asset rewards",
      inputs = dvpExample,
      totalRoundAppActivityWeight = 25000000L,
      totalFeaturedAssetWeight = 5000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("11850"),
        assetMintingAllowanceCC = BigDecimal("2370"),
        unclaimedMintingAllowanceCC = BigDecimal("97780"),
        issuancePerAppBurnCC = BigDecimal("0.79"),
        issuancePerAppBurnCCPerMB = BigDecimal("474"),
        issuancePerAssetBurnCC = BigDecimal("0.79"),
        issuancePerAssetBurnCCPerMB = BigDecimal("474"),
        appRewardCouponThresholdCC = BigDecimal(0),
      ),
    ),
    // 8,000 DvPs → 200 MB app burn (120,000 CC), 40 MB asset burn (24,000 CC).
    // App cap binds; asset side exhausts the leftover (17,200 CC) before its cap kicks in.
    TestCase(
      label = "dvpExample: 8k DvPs, asset rewards scaled by leftover",
      inputs = dvpExample,
      totalRoundAppActivityWeight = 200000000L,
      totalFeaturedAssetWeight = 40000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("94800"),
        assetMintingAllowanceCC = BigDecimal("17200"),
        unclaimedMintingAllowanceCC = BigDecimal(0),
        issuancePerAppBurnCC = BigDecimal("0.79"),
        issuancePerAppBurnCCPerMB = BigDecimal("474"),
        issuancePerAssetBurnCC = BigDecimal("0.7166666667"),
        issuancePerAssetBurnCCPerMB = BigDecimal("430.00000002"),
        appRewardCouponThresholdCC = BigDecimal(0),
      ),
    ),
    // 20,000 DvPs → 500 MB app burn (300,000 CC), 100 MB asset burn (60,000 CC).
    // App side allowance binds (112,000 < 0.79 × 300,000); no leftover for asset.
    TestCase(
      label = "dvpExample: 20k DvPs, no asset rewards",
      inputs = dvpExample,
      totalRoundAppActivityWeight = 500000000L,
      totalFeaturedAssetWeight = 100000000L,
      expected = RewardIssuanceParams(
        appMintingAllowanceCC = BigDecimal("112000"),
        assetMintingAllowanceCC = BigDecimal(0),
        unclaimedMintingAllowanceCC = BigDecimal(0),
        issuancePerAppBurnCC = BigDecimal("0.3733333333"),
        issuancePerAppBurnCCPerMB = BigDecimal("223.99999998"),
        issuancePerAssetBurnCC = BigDecimal(0),
        issuancePerAssetBurnCCPerMB = BigDecimal(0),
        appRewardCouponThresholdCC = BigDecimal(0),
      ),
    ),
  )
}

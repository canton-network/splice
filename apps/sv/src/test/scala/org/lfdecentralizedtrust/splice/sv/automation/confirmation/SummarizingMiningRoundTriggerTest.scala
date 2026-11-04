// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import com.digitalasset.canton.BaseTest
import org.lfdecentralizedtrust.splice.http.v0.definitions.RewardAccountingActivityTotalsOk
import org.lfdecentralizedtrust.splice.sv.store.AppRewardCouponsSum
import org.scalatest.wordspec.AnyWordSpec

class SummarizingMiningRoundTriggerTest extends AnyWordSpec with BaseTest {

  private def mkTotals(totalAppRewardFromAppActivity: String): RewardAccountingActivityTotalsOk =
    RewardAccountingActivityTotalsOk(
      status = "Ok",
      roundNumber = 10L,
      totalAppActivityWeight = 1L,
      totalAssetActivityWeight = 1L,
      activePartiesCount = 1L,
      activityRecordsCount = 1L,
      totalAppRewardFromAppActivity = totalAppRewardFromAppActivity,
      totalAppRewardFromAssetActivity = "17.2500000000",
      issuancePerFeaturedAppActivity = "0.1",
      issuancePerFeaturedAssetActivity = "0.1",
      totalAppRewardMintingAllowance = "999.9999999999",
      totalAppRewardThresholded = "999.9999999999",
      totalAppRewardUnclaimed = "0",
      rewardedAppProviderPartiesCount = 1L,
    )

  "appRewardCouponsFromScanTotals" should {
    // Regression guard: the featured burn must come from
    // totalAppRewardFromAppActivity, not MintingAllowance + Thresholded (those
    // now combine app+asset under TBAR v1.1).
    "use totalAppRewardFromAppActivity for the featured burn" in {
      SummarizingMiningRoundTrigger.appRewardCouponsFromScanTotals(
        mkTotals(totalAppRewardFromAppActivity = "42.5000000000")
      ) shouldBe AppRewardCouponsSum(
        featured = BigDecimal("42.5000000000"),
        unfeatured = BigDecimal(0),
      )
    }
  }
}

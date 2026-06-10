// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.RewardCouponV2
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllValidatorConfigs
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.util.{
  SpliceUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.automation.{
  CollectRewardsAndMergeAmuletsTrigger,
  RewardSharingTrigger,
}
import org.lfdecentralizedtrust.splice.wallet.config.{
  AppRewardBeneficiaryConfig,
  RewardSharingConfig,
}

import java.time.Duration

/** Tests the RewardSharingTrigger in isolation: verifies that unassigned
  * RewardCouponV2 contracts are correctly assigned to beneficiaries with
  * the right amounts when the TTL threshold is reached. Minting triggers
  * are paused to focus on assignment correctness and batching.
  *
  * See also [[WalletRewardsTimeBasedIntegrationTest]] which tests the
  * interaction between sharing and minting triggers end-to-end.
  */
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
class RewardSharingTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withAmuletPrice(SpliceUtil.damlDecimal(1.0))
      .addConfigTransforms((_, config) => {
        val aliceParticipant =
          ConfigTransforms.getParticipantIds(config.parameters.clock)("alice_validator_user")
        val alicePartyHint =
          config.validatorApps(InstanceName.tryCreate("aliceValidator")).validatorPartyHint.value
        val aliceValidatorPartyId = PartyId.tryFromProtoPrimitive(
          s"${alicePartyHint}::${aliceParticipant.split("::").last}"
        )
        val bobParticipant =
          ConfigTransforms.getParticipantIds(config.parameters.clock)("bob_validator_user")
        val bobPartyHint =
          config.validatorApps(InstanceName.tryCreate("bobValidator")).validatorPartyHint.value
        val bobPartyId = PartyId.tryFromProtoPrimitive(
          s"${bobPartyHint}::${bobParticipant.split("::").last}"
        )
        updateAllValidatorConfigs { case (name, c) =>
          if (name == "aliceValidator") {
            c.copy(
              rewardSharingConfigByParty = Map(
                aliceValidatorPartyId.toProtoPrimitive -> RewardSharingConfig(
                  minTtlAfterSharing = NonNegativeFiniteDuration.ofHours(30),
                  beneficiaries = Seq(
                    AppRewardBeneficiaryConfig(bobPartyId, BigDecimal(0.4))
                  ),
                )
              )
            )
          } else c
        }(config)
      })

  override protected lazy val sanityChecksIgnoredRootCreates = Seq(
    RewardCouponV2.TEMPLATE_ID_WITH_PACKAGE_ID
  )

  override def runUpdateHistorySanityCheck: Boolean = false

  "RewardSharingTrigger assigns beneficiaries when TTL threshold reached" in { implicit env =>
    val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
    val bobValidatorParty = bobValidatorBackend.getValidatorPartyId()
    onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    onboardWalletUser(bobWalletClient, bobValidatorBackend)

    val aliceWalletAutomation = aliceValidatorBackend
      .userWalletAutomation(aliceValidatorWalletClient.config.ledgerApiUser)
      .futureValue
    val aliceSharingTrigger = aliceWalletAutomation.trigger[RewardSharingTrigger]
    val aliceMintingTrigger = aliceWalletAutomation.trigger[CollectRewardsAndMergeAmuletsTrigger]
    val bobMintingTrigger = bobValidatorBackend
      .userWalletAutomation(bobValidatorWalletClient.config.ledgerApiUser)
      .futureValue
      .trigger[CollectRewardsAndMergeAmuletsTrigger]

    def listV2Coupons() =
      aliceValidatorBackend.appState.walletManager
        .valueOrFail("WalletManager is expected to be defined")
        .lookupEndUserPartyWallet(aliceValidatorParty)
        .valueOrFail("Expected alice to have a wallet")
        .store
        .multiDomainAcsStore
        .listContracts(RewardCouponV2.COMPANION)
        .futureValue

    // Pause both minting triggers so assigned coupons are not consumed
    // before we can assert on them. Bob's trigger must also be paused
    // because V2 coupons can be minted immediately.
    setTriggersWithin(triggersToPauseAtStart = Seq(aliceMintingTrigger, bobMintingTrigger)) {
      // Pause the sharing trigger while creating coupons and advancing
      // time, so both coupons cross the TTL before the trigger polls.
      setTriggersWithin(triggersToPauseAtStart = Seq(aliceSharingTrigger)) {
        clue("Create unassigned RewardCouponV2 for alice's validator") {
          createRewardCouponsV2(
            Seq(
              (aliceValidatorParty, BigDecimal(10.0), None),
              (aliceValidatorParty, BigDecimal(5.0), None),
            ),
            ttl = Duration.ofHours(36),
          )
        }

        clue("Unassigned coupons exist before TTL threshold") {
          eventually() {
            listV2Coupons().filter(_.payload.beneficiary.isEmpty) should have size 2
          }
        }

        // Advance past the sharing threshold (36h - 30h = 6h)
        advanceTime(Duration.ofHours(7))
      }

      // Sharing trigger resumes — both coupons are past TTL, so the
      // trigger batches them in a single AssignBeneficiaries call.
      clue("Unassigned coupons are consumed and assigned coupons created") {
        eventually() {
          val allCoupons = listV2Coupons()

          allCoupons.filter(_.payload.beneficiary.isEmpty) shouldBe
            empty withClue "unassigned coupons should be consumed"

          // Each input coupon produces one assigned coupon per beneficiary
          val assigned = allCoupons
            .filter(_.payload.beneficiary.isPresent)
            .map(c => (c.payload.beneficiary.get(), BigDecimal(c.payload.amount)))
            .sorted

          assigned should contain theSameElementsAs Seq(
            (aliceValidatorParty.toProtoPrimitive, BigDecimal(6.0)), // 60% of 10.0
            (aliceValidatorParty.toProtoPrimitive, BigDecimal(3.0)), // 60% of 5.0
            (bobValidatorParty.toProtoPrimitive, BigDecimal(4.0)), // 40% of 10.0
            (bobValidatorParty.toProtoPrimitive, BigDecimal(2.0)), // 40% of 5.0
          ) withClue "one assigned coupon per input coupon per beneficiary"
        }
      }
    }
  }
}

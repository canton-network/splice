package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  AppRewardCoupon,
  RewardCouponV2,
  ValidatorRewardCoupon,
}
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
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.wallet.config.{
  AppRewardBeneficiaryConfig,
  RewardSharingConfig,
}

import scala.concurrent.duration.DurationInt

/** Tests end-to-end reward collection including reward sharing: verifies
  * that the sharing trigger correctly assigns beneficiaries with the right
  * amounts (batching multiple coupons), that the minting trigger does not
  * re-assign unshared coupons, and that balances reflect the minted rewards.
  */
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
class WalletRewardsTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // TODO (#965) remove and fix test failures
      .withAmuletPrice(walletAmuletPrice)
      .addConfigTransforms((_, config) => {
        def validatorPartyId(validatorUser: String, validatorName: String): PartyId = {
          val participant =
            ConfigTransforms.getParticipantIds(config.parameters.clock)(validatorUser)
          val partyHint =
            config.validatorApps(InstanceName.tryCreate(validatorName)).validatorPartyHint.value
          PartyId.tryFromProtoPrimitive(s"${partyHint}::${participant.split("::").last}")
        }
        val aliceValidatorPartyId = validatorPartyId("alice_validator_user", "aliceValidator")
        val bobValidatorPartyId = validatorPartyId("bob_validator_user", "bobValidator")
        updateAllValidatorConfigs { case (name, c) =>
          if (name == "aliceValidator") {
            // Alice shares 40% with bob; the implicit remainder (60%) goes to alice.
            c.copy(
              rewardSharingConfigByParty = Map(
                aliceValidatorPartyId.toProtoPrimitive -> RewardSharingConfig.BuiltIn(
                  minTtlAfterSharing = NonNegativeFiniteDuration.ofHours(30),
                  beneficiaries = Seq(
                    AppRewardBeneficiaryConfig(bobValidatorPartyId, BigDecimal(0.4))
                  ),
                )
              )
            )
          } else c
        }(config)
      })

  // TODO (#965) remove and fix test failures
  override def walletAmuletPrice = SpliceUtil.damlDecimal(1.0)

  override protected lazy val sanityChecksIgnoredRootCreates = Seq(
    AppRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
    ValidatorRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
    RewardCouponV2.TEMPLATE_ID_WITH_PACKAGE_ID,
  )

  "A wallet" should {

    "list and automatically collect app & validator rewards" in { implicit env =>
      val (alice, bob) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
      val bobValidatorParty = bobValidatorBackend.getValidatorPartyId()

      // Tap amulet and do a transfer from alice to bob
      aliceWalletClient.tap(walletAmuletToUsd(50))

      p2pTransfer(aliceWalletClient, bobWalletClient, bob, 40.0)
      // Rewards roughly match what we had before we set fees to zero
      createRewards(
        appRewards = Seq((aliceValidatorParty, 0.43, false)),
        validatorRewards = Seq((alice, 0.43)),
      )

      // Retrieve transferred amulet in bob's wallet and transfer part of it back to alice;
      // bob's validator will receive some app rewards
      eventually()(bobWalletClient.list().amulets should have size 1 withClue "amulets")
      p2pTransfer(bobWalletClient, aliceWalletClient, alice, 30.0)
      // Rewards roughly match what we had before we set fees to zero
      createRewards(
        appRewards = Seq((bobValidatorParty, 0.33, false)),
        validatorRewards = Seq((bob, 0.33)),
      )

      val bobV2Amount = BigDecimal(1000.0)
      val aliceV2Amounts = Seq(BigDecimal(10.0), BigDecimal(5.0))

      val openRounds = eventually() {
        import math.Ordering.Implicits.*
        val openRounds = sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .filter(_.payload.opensAt <= env.environment.clock.now.toInstant)
        openRounds should not be empty withClue "openRounds"
        openRounds
      }

      advanceTimeForRewardAutomationToRunForCurrentRound

      eventually(40.seconds) {
        bobValidatorWalletClient
          .listAppRewardCoupons() should have size 1 withClue "AppRewardCoupons"
        bobValidatorWalletClient
          .listValidatorRewardCoupons() should have size 1 withClue "ValidatorRewardCoupons"
        aliceValidatorWalletClient
          .listAppRewardCoupons() should have size 1 withClue "AppRewardCoupons"
        aliceValidatorWalletClient
          .listValidatorRewardCoupons() should have size 1 withClue "ValidatorRewardCoupons"
        bobValidatorWalletClient
          .listValidatorLivenessActivityRecords() should have size openRounds.size.toLong withClue "bob ValidatorLivenessActivityRecords"
        aliceValidatorWalletClient
          .listValidatorLivenessActivityRecords() should have size openRounds.size.toLong withClue "alice ValidatorLivenessActivityRecords"
      }

      // Pause bob's faucet coupon trigger to avoid messing with balance computation
      bobValidatorBackend.validatorAutomation
        .trigger[ReceiveFaucetCouponTrigger]
        .pause()
        .futureValue

      val bobRewardTrigger = bobValidatorBackend
        .userWalletAutomation(bobValidatorWalletClient.config.ledgerApiUser)
        .futureValue
        .trigger[CollectRewardsAndMergeAmuletsTrigger]

      // Pause bob's minting trigger so we can observe his assigned
      // (unminted) coupon from alice's sharing, while alice's triggers
      // run freely (sharing + minting). V2 coupons must be created after
      // pausing because they can be minted immediately.
      val prevBobBalance = setTriggersWithin(triggersToPauseAtStart = Seq(bobRewardTrigger)) {
        // Create unassigned V2 coupons for both validators.
        // Bob (no sharing config) → his coupon stays unminted (trigger paused).
        // Alice (has sharing config, 2 coupons) → shared then minted,
        // exercising batching via additionalCoupons in AssignBeneficiaries.
        clue("Create unassigned RewardCouponV2 for both validators") {
          createRewardCouponsV2(
            Seq(
              (bobValidatorParty, bobV2Amount, None)
            ) ++ aliceV2Amounts.map((aliceValidatorParty, _, None))
          )
        }

        // Capture balance after coupon creation but before advancement,
        // since bob's trigger is paused and won't mint during advancement.
        val balance = bobValidatorWalletClient.balance().unlockedQty

        // Round advancement is needed for the treasury's transfer context
        // (non-V2 reward collection), not for V2 coupons specifically.
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening
        advanceTimeForRewardAutomationToRunForCurrentRound

        clue("Alice's V2 coupons are shared with correct amounts per beneficiary") {
          val aliceWallet = aliceValidatorBackend.appState.walletManager
            .valueOrFail("WalletManager is expected to be defined")
            .lookupEndUserPartyWallet(aliceValidatorParty)
            .valueOrFail("Expected alice to have a wallet")
          eventually() {
            val allCoupons = aliceWallet.store.multiDomainAcsStore
              .listContracts(RewardCouponV2.COMPANION)
              .futureValue
              .filter(_.payload.provider == aliceValidatorParty.toProtoPrimitive)

            allCoupons.filter(_.payload.beneficiary.isEmpty) shouldBe
              empty withClue "Unassigned coupons should be consumed by sharing trigger"

            // Each input coupon produces one assigned coupon per beneficiary
            val assigned = allCoupons
              .filter(_.payload.beneficiary.isPresent)
              .map(c => (c.payload.beneficiary.get(), BigDecimal(c.payload.amount)))

            assigned should contain theSameElementsAs Seq(
              (aliceValidatorParty.toProtoPrimitive, BigDecimal(6.0)), // 60% of 10.0
              (aliceValidatorParty.toProtoPrimitive, BigDecimal(3.0)), // 60% of 5.0
              (bobValidatorParty.toProtoPrimitive, BigDecimal(4.0)), // 40% of 10.0
              (bobValidatorParty.toProtoPrimitive, BigDecimal(2.0)), // 40% of 5.0
            ) withClue "one assigned coupon per input coupon per beneficiary"
          }
        }

        clue("Bob has unminted assigned coupon from alice's sharing") {
          val bobWallet = bobValidatorBackend.appState.walletManager
            .valueOrFail("WalletManager is expected to be defined")
            .lookupEndUserPartyWallet(bobValidatorParty)
            .valueOrFail("Expected bob to have a wallet")
          eventually() {
            val bobAssigned = bobWallet.store.multiDomainAcsStore
              .listContracts(RewardCouponV2.COMPANION)
              .futureValue
              .filter { c =>
                c.payload.provider == aliceValidatorParty.toProtoPrimitive &&
                c.payload.beneficiary.isPresent &&
                c.payload.beneficiary.get() == bobValidatorParty.toProtoPrimitive
              }
            bobAssigned should not be empty withClue
              "Bob should have an assigned coupon from alice's sharing"
          }
        }

        balance
      }

      // Verify minting with no-sharing-config: bob's own V2 coupon is
      // minted directly, and alice's shared 40% is also minted into
      // bob's balance.
      val aliceShareToBob = aliceV2Amounts.sum * BigDecimal(0.4)
      val expectedV2Delta = bobV2Amount + aliceShareToBob
      clue("Bob's balance reflects his own V2 coupon + alice's shared 40%") {
        eventually() {
          val newBobBalance = bobValidatorWalletClient.balance().unlockedQty
          val delta = newBobBalance - prevBobBalance
          // Delta must include at least the V2 coupons; may also include
          // faucet rewards earned after prevBobBalance was captured.
          delta should be >= expectedV2Delta withClue
            s"delta=$delta should be at least V2 coupons ($expectedV2Delta)"
        }
      }
    }
  }
}

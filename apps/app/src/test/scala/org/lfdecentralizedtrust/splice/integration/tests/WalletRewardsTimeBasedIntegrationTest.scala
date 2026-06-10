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
import org.lfdecentralizedtrust.splice.util.{SpliceUtil, TimeTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.config.{
  AppRewardBeneficiaryConfig,
  RewardSharingConfig,
}

import scala.concurrent.duration.DurationInt

class WalletRewardsTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil {

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
        val bobPartyId = validatorPartyId("bob_validator_user", "bobValidator")
        updateAllValidatorConfigs { case (name, c) =>
          if (name == "aliceValidator") {
            // Specify a RewardConfig for Alice's validator,
            // so that unassigned RewardCouponV2 should not get minted
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

      val rewardCouponV2Amount = BigDecimal(1000.0)

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

      // avoid messing with the computation of balance
      bobValidatorBackend.validatorAutomation
        .trigger[ReceiveFaucetCouponTrigger]
        .pause()
        .futureValue

      val prevBalance = bobValidatorWalletClient.balance().unlockedQty

      // Create unassigned V2 coupons after capturing prevBalance, so the
      // minted V2 amount is reflected in the balance delta.
      // Bob (no sharing config) → treasury mints unassigned coupons.
      // Alice (has sharing config) → treasury does NOT mint unassigned coupons.
      clue("Create unassigned RewardCouponV2 for both validators") {
        createRewardCouponsV2(
          Seq(
            (bobValidatorParty, rewardCouponV2Amount, None),
            (aliceValidatorParty, rewardCouponV2Amount, None),
          )
        )
      }

      // Bob's validator collects rewards
      // it takes 3 ticks for the IssuingMiningRound 1 to be created and open.
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening
      advanceTimeForRewardAutomationToRunForCurrentRound

      eventually() {
        bobValidatorWalletClient
          .listAppRewardCoupons() should have size 0 withClue "AppRewardCoupons"
        bobValidatorWalletClient
          .listValidatorRewardCoupons() should have size 0 withClue "ValidatorRewardCoupons"
        bobValidatorWalletClient
          .listValidatorLivenessActivityRecords() should have size 0 withClue "ValidatorLivenessActivityRecords"

        val newBalance = bobValidatorWalletClient.balance().unlockedQty

        // We just check that the balance has increased by roughly the right amount,
        // rather then repeating the calculation for the reward amount.
        // 2.85 USD per faucet coupon; RewardCouponV2 amount is added directly.
        val faucetCouponAmountUsd = 2.85 * openRounds.size
        assertInRange(
          newBalance - prevBalance,
          (
            walletUsdToAmulet(-0.1 + faucetCouponAmountUsd) + rewardCouponV2Amount,
            walletUsdToAmulet(0.5 + faucetCouponAmountUsd) + rewardCouponV2Amount,
          ),
        )
      }

      clue("Alice's unassigned V2 coupon is NOT minted (sharing config present)") {
        val aliceWallet = aliceValidatorBackend.appState.walletManager
          .valueOrFail("WalletManager is expected to be defined")
          .lookupEndUserPartyWallet(aliceValidatorParty)
          .valueOrFail("Expected alice to have a wallet")
        val unassigned = aliceWallet.store.multiDomainAcsStore
          .listContracts(RewardCouponV2.COMPANION)
          .futureValue
          .filter(c =>
            c.payload.provider == aliceValidatorParty.toProtoPrimitive &&
              c.payload.beneficiary.isEmpty
          )
        unassigned should have size 1 withClue
          "Unassigned V2 coupon should remain (not minted) because sharing config is present"
      }
    }
  }
}

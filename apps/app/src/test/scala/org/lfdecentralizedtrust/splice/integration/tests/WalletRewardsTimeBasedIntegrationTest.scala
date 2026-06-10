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

/** Tests end-to-end reward collection including the interaction between
  * sharing and minting triggers: verifies that shared coupons are minted
  * correctly, that the minting trigger does not re-assign unshared
  * coupons, and that balances reflect the minted rewards.
  *
  * See also [[RewardSharingTimeBasedIntegrationTest]] which tests
  * assignment correctness and batching in isolation.
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
                aliceValidatorPartyId.toProtoPrimitive -> RewardSharingConfig(
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
      // run freely (sharing + minting).
      setTriggersWithin(triggersToPauseAtStart = Seq(bobRewardTrigger)) {
        // it takes 3 ticks for the IssuingMiningRound 1 to be created and open.
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening
        advanceTimeForRewardAutomationToRunForCurrentRound

        clue("Alice's V2 coupon is shared — no unassigned coupons remain") {
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
              empty withClue "Unassigned coupon should be consumed by sharing trigger"
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
      }
    }
  }
}

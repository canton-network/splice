package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
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
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.RewardSharingTrigger
import org.lfdecentralizedtrust.splice.wallet.config.{RewardSharingConfig, SharingAutomation}

/** Verifies that a party configured with external reward-sharing automation is
  * left untouched by the validator: the built-in RewardSharingTrigger is not
  * registered, and unassigned RewardCouponV2 are neither shared nor collected
  * (mintUnassignedRewardCouponsV2 = false). An off-node automation owns the
  * sharing for such a party.
  */
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
class WalletExternalRewardSharingTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
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
        updateAllValidatorConfigs { case (name, c) =>
          if (name == "aliceValidator") {
            // Alice delegates sharing to an off-node automation. No beneficiaries
            // may be set (external + beneficiaries is a hard config error).
            c.copy(
              rewardSharingConfigByParty = Map(
                aliceValidatorPartyId.toProtoPrimitive -> RewardSharingConfig(
                  sharingAutomation = SharingAutomation.External
                )
              )
            )
          } else c
        }(config)
      })

  override def walletAmuletPrice: java.math.BigDecimal = SpliceUtil.damlDecimal(1.0)

  "A wallet with external reward-sharing automation" should {
    "not register the built-in sharing trigger and not touch unassigned coupons" in {
      implicit env =>
        onboardAliceAndBob()
        waitForWalletUser(aliceValidatorWalletClient)
        val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

        // External mode: the built-in RewardSharingTrigger must not be registered.
        clue("No RewardSharingTrigger is registered for the external party") {
          val aliceAutomation = aliceValidatorBackend
            .userWalletAutomation(aliceValidatorWalletClient.config.ledgerApiUser)
            .futureValue
          aliceAutomation.triggers[RewardSharingTrigger] shouldBe empty
        }

        // Pause alice's faucet trigger to keep coupon set stable during advancement.
        aliceValidatorBackend.validatorAutomation
          .trigger[ReceiveFaucetCouponTrigger]
          .pause()
          .futureValue

        val aliceV2Amount = BigDecimal(10.0)

        clue("Create an unassigned RewardCouponV2 for the external party") {
          createRewardCouponsV2(Seq((aliceValidatorParty, aliceV2Amount, None)))
        }

        // Give the automation a real chance to run.
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening
        advanceTimeForRewardAutomationToRunForCurrentRound

        // The coupon must remain: unassigned (not shared) and on-ledger (not
        // collected into amulet, since mintUnassignedRewardCouponsV2 = false).
        clue("Unassigned coupon is neither shared nor collected") {
          val aliceWallet = aliceValidatorBackend.appState.walletManager
            .valueOrFail("WalletManager is expected to be defined")
            .lookupEndUserPartyWallet(aliceValidatorParty)
            .valueOrFail("Expected alice to have a wallet")
          eventually() {
            val couponsForAlice = aliceWallet.store.multiDomainAcsStore
              .listContracts(RewardCouponV2.COMPANION)
              .futureValue
              .filter(_.payload.provider == aliceValidatorParty.toProtoPrimitive)

            couponsForAlice should have size 1 withClue
              "the single unassigned coupon must still be present"
            couponsForAlice.filter(_.payload.beneficiary.isPresent) shouldBe
              empty withClue "external mode must not assign beneficiaries"
            BigDecimal(couponsForAlice.head.payload.amount) shouldBe aliceV2Amount
          }
        }
    }
  }
}

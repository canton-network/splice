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
import org.lfdecentralizedtrust.splice.util.{SpliceUtil, TimeTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.config.{
  AppRewardBeneficiaryConfig,
  RewardSharingConfig,
}

import java.time.Duration
import java.util.Optional
import scala.jdk.CollectionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
class RewardSharingTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil {

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

    def listV2Coupons() =
      aliceValidatorBackend.appState.walletManager
        .valueOrFail("WalletManager is expected to be defined")
        .lookupEndUserPartyWallet(aliceValidatorParty)
        .valueOrFail("Expected alice to have a wallet")
        .store
        .multiDomainAcsStore
        .listContracts(RewardCouponV2.COMPANION)
        .futureValue

    clue("Create unassigned RewardCouponV2 for alice's validator") {
      val openRound = sv1ScanBackend.getLatestOpenMiningRound(env.environment.clock.now)
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(dsoParty),
        commands = new RewardCouponV2(
          dsoParty.toProtoPrimitive,
          aliceValidatorParty.toProtoPrimitive,
          openRound.payload.round,
          BigDecimal(10.0).bigDecimal,
          env.environment.clock.now.plus(Duration.ofHours(36)).toInstant,
          true,
          Optional.empty(),
        ).create.commands.asScala.toSeq,
      )
    }

    clue("Unassigned coupon exists before TTL threshold") {
      eventually() {
        listV2Coupons().filter(_.payload.beneficiary.isEmpty) should have size 1
      }
    }

    // Advance past the sharing threshold (36h - 30h = 6h)
    advanceTime(Duration.ofHours(7))

    clue("Unassigned coupon is consumed and assigned coupons created") {
      eventually() {
        val allCoupons = listV2Coupons()

        allCoupons.filter(_.payload.beneficiary.isEmpty) shouldBe
          empty withClue "unassigned coupons should be consumed"

        val assigned = allCoupons.filter(_.payload.beneficiary.isPresent)
        assigned should have size 2 withClue "one coupon per beneficiary"

        val byBeneficiary = assigned
          .map(c => c.payload.beneficiary.get() -> BigDecimal(c.payload.amount))
          .toMap

        byBeneficiary should contain key bobValidatorParty.toProtoPrimitive withClue "bob should receive a coupon"
        byBeneficiary should contain key aliceValidatorParty.toProtoPrimitive withClue "alice should receive remainder"

        byBeneficiary(bobValidatorParty.toProtoPrimitive) shouldBe BigDecimal(
          4.0
        ) withClue "bob gets 40%"
        byBeneficiary(aliceValidatorParty.toProtoPrimitive) shouldBe BigDecimal(
          6.0
        ) withClue "alice gets 60%"
      }
    }
  }
}

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import java.time.Duration
import java.util.Optional
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  RewardConfig,
  RewardVersion,
  USD,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.GetRewardAccountingBatchResponse
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.{
  CalculateRewardsDryRunTrigger,
  CalculateRewardsTrigger,
}
import org.lfdecentralizedtrust.splice.sv.config.InitialRewardConfig
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  AmuletConfigUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}

// This test focuses on the SV app side triggers testing
// - Turning on/off of dry-run and minting-version in rewardConfig
//   And confirming that rewards processing works.
//
// Later this test would be extended to cover unhide, expire, etc
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
class TrafficBasedRewardsSvAppTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with TimeTestUtil
    with AmuletConfigUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        ConfigTransforms.withRewardConfig(
          InitialRewardConfig(
            dryRunVersion = None,
            appRewardCouponThreshold = BigDecimal("0"),
          )
        )(config)
      )

  "Enable, disable of dryRunVersion/mintingVersion take effect at round closure" in {
    implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

      aliceWalletClient.tap(20000)

      grantFeaturedAppRight(aliceWalletClient)
      grantFeaturedAppRight(bobWalletClient)

      for (round <- 1 to 3) {
        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(round.toLong)
      }

      // oldest=3: rounds 3,4,5 open.
      // Next round to open is R6, it will have dryRun enabled
      clue("vote to enable dryRunVersion") {
        changeRewardConfig(enableDryRun = true)
      }

      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(4)
      doTransfer(bobParty)

      // oldest=4: rounds 4,5,6 open.
      // R7 will have the disabled config.
      clue("vote to disable dryRunVersion") {
        changeRewardConfig(enableDryRun = false)
      }

      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(5)
      doTransfer(bobParty)

      // oldest=5: rounds 5,6,7 open. R8 will have
      // both dryRunVersion and mintingVersion set.
      clue("vote to enable dryRunVersion + mintingVersion") {
        changeRewardConfig(enableDryRun = true, enableMinting = true)
      }

      val calculateRewardsDryRunTrigger =
        sv1Backend.dsoAutomation.trigger[CalculateRewardsDryRunTrigger]
      val calculateRewardsTrigger =
        sv1Backend.dsoAutomation.trigger[CalculateRewardsTrigger]

      // Create activity for 6, 7, and 8 and confirm creation of CalculateRewardsV2
      setTriggersWithin(
        triggersToPauseAtStart = Seq(calculateRewardsDryRunTrigger, calculateRewardsTrigger)
      ) {
        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(6)
        doTransfer(bobParty)

        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(7)
        doTransfer(bobParty)

        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(8)
        doTransfer(bobParty)

        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(9)
        doTransfer(bobParty)

        clue("CalculateRewardsV2 are created for rounds, 6 and 8") {
          eventually() {
            val v2s = sv1Backend.appState.dsoStore.listCalculateRewardsV2().futureValue
            v2s.map(_.payload.round.number) should contain(6L)
            v2s.map(_.payload.round.number) should not contain 7L
            v2s
              .filter(_.payload.round.number == 8L)
              .map(_.payload.dryRun)
              .toSet shouldBe Set(true, false)
          }
        }
      }

      clue("Alice and Bob have minting allowances for R6") {
        eventually() {
          val hash = sv1ScanBackend.getRewardAccountingRootHash(6L).value.rootHash
          val providers = walkBatch(6L, hash).map(_.provider)
          providers should contain(aliceParty.toProtoPrimitive)
          providers should contain(bobParty.toProtoPrimitive)
        }
      }

      clue("Alice and Bob have minting allowances for R8") {
        eventually() {
          val hash = sv1ScanBackend.getRewardAccountingRootHash(8L).value.rootHash
          val providers = walkBatch(8L, hash).map(_.provider)
          providers should contain(aliceParty.toProtoPrimitive)
          providers should contain(bobParty.toProtoPrimitive)
        }
      }

      clue("Scan computes activity totals even for rounds with no dryRun/mintingVersion set") {
        eventually() {
          sv1ScanBackend.getRewardAccountingActivityTotals(5L) shouldBe defined
          sv1ScanBackend.getRewardAccountingActivityTotals(7L) shouldBe defined
        }
      }

      clue("All CalculateRewardsV2 and ProcessRewardsV2 contracts consumed") {
        eventually() {
          sv1Backend.appState.dsoStore.listCalculateRewardsV2().futureValue shouldBe empty
          sv1Backend.appState.dsoStore.listProcessRewardsV2().futureValue shouldBe empty
        }
      }

      clue("Alice and Bob received RewardCouponV2 for R8") {
        eventually() {
          val coupons = sv1Backend.appState.dsoStore.listRewardCouponsV2().futureValue
          coupons.filter(c =>
            c.payload.round.number == 8L && c.payload.provider == aliceParty.toProtoPrimitive
          ) should not be empty
          coupons.filter(c =>
            c.payload.round.number == 8L && c.payload.provider == bobParty.toProtoPrimitive
          ) should not be empty
        }
      }
  }

  private def doTransfer(
      bobParty: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val offerCid = aliceWalletClient.createTransferOffer(
      bobParty,
      BigDecimal(10.0),
      "activity",
      CantonTimestamp.now().plus(Duration.ofMinutes(1)),
      s"transfer-${scala.util.Random.nextInt()}",
    )
    bobWalletClient.acceptTransferOffer(offerCid)
  }

  private def walkBatch(
      round: Long,
      hash: String,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Seq[definitions.RewardAccountingMintingAllowance] =
    sv1ScanBackend.getRewardAccountingBatch(round, hash).toList.flatMap {
      case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfBatches(b) =>
        b.childHashes.flatMap(h => walkBatch(round, h))
      case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfMintingAllowances(b) =>
        b.mintingAllowances.toSeq
    }

  private def assertOldestOpenRound(
      expected: Long
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue(s"Asserting oldest open round=$expected") {
      eventually() {
        val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        val roundNumbers = openRounds.map(_.contract.payload.round.number.toLong).sorted
        roundNumbers should have size 3
        roundNumbers.head shouldBe expected
      }
    }
  }

  private def changeRewardConfig(
      enableDryRun: Boolean,
      enableMinting: Boolean = false,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val amuletRules = sv1Backend.getDsoInfo().amuletRules
    val existing = AmuletConfigSchedule(amuletRules).getConfigAsOf(env.environment.clock.now)
    val rc = existing.rewardConfig.get()
    val newRc = new RewardConfig(
      if (enableMinting) RewardVersion.REWARDVERSION_TRAFFICBASEDAPPREWARDS
      else rc.mintingVersion,
      if (enableDryRun) Optional.of(RewardVersion.REWARDVERSION_TRAFFICBASEDAPPREWARDS)
      else Optional.empty[RewardVersion](),
      rc.batchSize,
      rc.rewardCouponTimeToLive,
      rc.appRewardCouponThreshold,
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
    )
    setAmuletConfig(Seq((None, newConfig, existing)))
    eventually() {
      sv1Backend.listVoteRequests() shouldBe empty
    }
  }

}

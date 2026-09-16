package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  Amulet,
  SvRewardCoupon,
  UnclaimedReward,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.util.SpliceUtil.defaultIssuanceCurve
import org.lfdecentralizedtrust.splice.util.{
  SvTestUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import com.digitalasset.canton.logging.SuppressionRule.LevelAndAbove
import org.slf4j.event.Level

class DisabledWalletTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil
    with SvTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms(
        (_, conf) =>
          conf.copy(validatorApps = conf.validatorApps.map {
            case (name, validatorConf) if name.unwrap == "sv1Validator" =>
              name -> validatorConf.copy(enableWallet = false)
            case x => x
          }),
        (_, conf) =>
          ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Sv)(
            // disable jitter and run reward automation as soon as possible
            _.copy(enableNewRewardTriggerScheduling = false)
          )(conf),
      )
      .withManualStart

  "Disabled wallet" in { implicit env =>
    clue("Start apps, wallet automations should not start up") {
      loggerFactory.assertLogsSeq(LevelAndAbove(Level.DEBUG))(
        startAllSync(sv1Backend, sv1ValidatorBackend, sv1ScanBackend),
        logs => {
          inside(logs) { case _logs =>
            _logs.exists(log =>
              log.loggerName.matches(
                s".*${classOf[TreasuryService].getSimpleName}.*SV=sv1.*"
              ) || log.loggerName.matches(
                s".*${classOf[CollectRewardsAndMergeAmuletsTrigger].getSimpleName}.*SV=sv1.*"
              )
            ) should be(false)
          }
        },
      )
    }

    clue("HTTP server should not start up") {
      assertThrowsAndLogsCommandFailures(
        sv1WalletClient.list() should be(1),
        log =>
          log.message should include(
            "The requested resource could not be found"
          ),
      )
    }

    clue("Rewards should end up in unclaimed rewards pool") {
      val currentRound =
        sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .minBy(_.contract.payload.round.number)
          .contract
          .payload
          .round
          .number
      val expectedMinAmount =
        BigDecimal(
          computeSvRewardInRound0(
            defaultIssuanceCurve().initialValue,
            defaultTickDuration,
            dsoSize = 1,
          )
        ) - smallAmount

      clue(s"SvRewardCoupon for round $currentRound gets created") {
        eventually() {
          sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(SvRewardCoupon.COMPANION)(
              dsoParty,
              coupon => {
                coupon.data.sv == sv1Backend.getDsoInfo().svParty.toProtoPrimitive &&
                coupon.data.round.number == currentRound
              },
            ) should not be empty
        }
      }

      actAndCheck(
        "advance until round is closed", {
          // move round to issuing
          advanceRoundsToNextRoundOpening
          // move to opensAt of issuing round
          advanceRoundsToNextRoundOpening
          // move current round to closed
          advanceRoundsToNextRoundOpening
          advanceRoundsToNextRoundOpening
        },
      )(
        s"Check that there is at least one unclaimed reward larger than $expectedMinAmount",
        _ => {
          sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(UnclaimedReward.COMPANION)(
              dsoParty,
              reward => BigDecimal(reward.data.amount) >= expectedMinAmount,
            ) should not be empty withClue s"UnclaimedRewards >= $expectedMinAmount"

          sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(Amulet.COMPANION)(dsoParty, _ => true) shouldBe empty withClue "Amulets"
        },
      )
    }

  }

}

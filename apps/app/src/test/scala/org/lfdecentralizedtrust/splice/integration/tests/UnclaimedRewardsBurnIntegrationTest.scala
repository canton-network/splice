package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  UnclaimedActivityRecord,
  UnclaimedReward,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.instruction.UnclaimedRewardsBurnInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_CreateUnclaimedRewardsBurnInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_CreateUnclaimedRewardsBurnInstruction
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  ExecuteUnclaimedRewardsBurnInstructionTrigger,
  ExpireIssuingMiningRoundTrigger,
  ExpireRewardCouponV2Trigger,
  ExpireRewardCouponsTrigger,
}
import org.lfdecentralizedtrust.splice.util.{TriggerTestUtil, WalletTestUtil}
import com.daml.ledger.javaapi.data.Identifier

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_32
class UnclaimedRewardsBurnIntegrationTest
    extends SvIntegrationTestBase
    with TriggerTestUtil
    with WalletTestUtil {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)

  override protected lazy val sanityChecksIgnoredRootCreates: Seq[Identifier] = Seq(
    UnclaimedReward.TEMPLATE_ID_WITH_PACKAGE_ID
  )

  private val reason = "escrowed SV missed milestone 1"

  private def burnTrigger(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend.dsoDelegateBasedAutomation.trigger[ExecuteUnclaimedRewardsBurnInstructionTrigger]

  private def rewardProducingTriggers(implicit env: SpliceTestConsoleEnvironment): Seq[Trigger] = {
    val automation = sv1Backend.dsoDelegateBasedAutomation
    Seq[Trigger](
      automation.trigger[ExpireIssuingMiningRoundTrigger],
      automation.trigger[ExpireRewardCouponsTrigger],
      automation.trigger[ExpireRewardCouponV2Trigger],
    )
  }

  private def poolTotal()(implicit env: SpliceTestConsoleEnvironment): BigDecimal =
    sv1Backend.participantClient.ledger_api_extensions.acs
      .filterJava(UnclaimedReward.COMPANION)(dsoParty)
      .map(r => BigDecimal(r.data.amount))
      .sum

  private def burnInstructions()(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend.participantClient.ledger_api_extensions.acs
      .filterJava(UnclaimedRewardsBurnInstruction.COMPANION)(dsoParty)

  private def voteToBurn(amount: BigDecimal)(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val sv1Party = sv1Backend.getDsoInfo().svParty
    val action = new ARC_DsoRules(
      new SRARC_CreateUnclaimedRewardsBurnInstruction(
        new DsoRules_CreateUnclaimedRewardsBurnInstruction(amount.bigDecimal, reason)
      )
    )
    sv1Backend.createVoteRequest(
      sv1Party.toProtoPrimitive,
      action,
      "url",
      reason,
      sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
      None,
    )
  }

  "Unclaimed rewards get burned once the pool covers the voted amount" in { implicit env =>
    val sv1UserId = sv1WalletClient.config.ledgerApiUser
    val amountToBurn = poolTotal() + BigDecimal(15)

    setTriggersWithin(triggersToPauseAtStart = (burnTrigger: Trigger) +: rewardProducingTriggers) {
      actAndCheck(
        "Vote to burn more than the pool holds",
        voteToBurn(amountToBurn),
      )(
        "the vote creates a burn instruction with the voted amount and reason",
        _ => {
          val instruction = burnInstructions().loneElement.data
          BigDecimal(instruction.amount) shouldBe amountToBurn
          instruction.reason shouldBe reason
          sv1Backend.appState.dsoStore
            .listUnclaimedRewardsBurnInstructions(PageLimit.tryCreate(10))
            .futureValue should have length 1
        },
      )

      clue("The burn trigger has no task while the pool is short") {
        runExpectingNoTask(burnTrigger)
      }

      actAndCheck(
        "Add enough unclaimed rewards to cover the burn",
        Seq(BigDecimal(12), BigDecimal(5)).foreach(amount =>
          createUnclaimedReward(
            sv1ValidatorBackend.participantClientWithAdminToken,
            sv1UserId,
            amount,
          )
        ),
      )(
        "the pool covers the burn",
        _ => poolTotal() should be >= amountToBurn,
      )

      val poolBeforeBurn = poolTotal()
      runUntilTaskSucceeds(burnTrigger)

      burnInstructions() shouldBe empty
      poolTotal() shouldBe poolBeforeBurn - amountToBurn
      sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(UnclaimedActivityRecord.COMPANION)(dsoParty) shouldBe empty
    }
  }

  "A vote request to burn a non-positive amount is rejected" in { implicit env =>
    assertThrowsAndLogsCommandFailures(
      voteToBurn(BigDecimal(0)),
      _.errorMessage should include regex ("400 Bad Request .* must be positive"),
    )
    burnInstructions() shouldBe empty
  }
}

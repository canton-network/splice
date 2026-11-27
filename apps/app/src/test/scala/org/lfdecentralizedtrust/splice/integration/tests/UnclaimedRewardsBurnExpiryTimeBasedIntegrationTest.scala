package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.UnclaimedReward
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.instruction.UnclaimedRewardsBurnInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_CreateUnclaimedRewardsBurnInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_CreateUnclaimedRewardsBurnInstruction
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}

import java.time.Duration

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_32
class UnclaimedRewardsBurnExpiryTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)

  private def poolTotal()(implicit env: SpliceTestConsoleEnvironment): BigDecimal =
    sv1Backend.participantClient.ledger_api_extensions.acs
      .filterJava(UnclaimedReward.COMPANION)(dsoParty)
      .map(r => BigDecimal(r.data.amount))
      .sum

  private def burnInstructions()(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend.participantClient.ledger_api_extensions.acs
      .filterJava(UnclaimedRewardsBurnInstruction.COMPANION)(dsoParty)

  "An UnclaimedRewardsBurnInstruction gets expired without burning" in { implicit env =>
    val sv1Party = sv1Backend.getDsoInfo().svParty
    val initialPool = poolTotal()
    val amountToBurn = initialPool + BigDecimal(1000)

    val (_, instruction) = actAndCheck(
      "Vote to burn more than the pool holds",
      sv1Backend.createVoteRequest(
        sv1Party.toProtoPrimitive,
        new ARC_DsoRules(
          new SRARC_CreateUnclaimedRewardsBurnInstruction(
            new DsoRules_CreateUnclaimedRewardsBurnInstruction(
              amountToBurn.bigDecimal,
              "escrowed SV missed milestone 1",
            )
          )
        ),
        "url",
        "escrowed SV missed milestone 1",
        sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
        None,
      ),
    )(
      "the burn instruction waits because the pool is short",
      _ => {
        poolTotal() shouldBe initialPool
        burnInstructions().loneElement
      },
    )

    actAndCheck(
      "Advance time past the instruction's expiry", {
        advanceTime(
          Duration
            .between(getLedgerTime.toInstant, instruction.data.expiresAt)
            .plusSeconds(1)
        )
        advanceTime(Duration.ofSeconds(5))
      },
    )(
      "SV automation archives the expired instruction without burning from the pool",
      _ => {
        burnInstructions() shouldBe empty
        poolTotal() should be >= initialPool
      },
    )
  }
}

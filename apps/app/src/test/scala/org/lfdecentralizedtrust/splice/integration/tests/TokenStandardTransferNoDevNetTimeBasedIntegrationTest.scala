package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{
  SvTestUtil,
  SynchronizerFeesTestUtil,
  TimeTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.validator.automation.{
  ReceiveFaucetCouponTrigger,
  TopupMemberTrafficTrigger,
}

import java.time.Duration
import java.util.UUID

class TokenStandardTransferNoDevNetTimeBasedIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with SynchronizerFeesTestUtil
    with TimeTestUtil
    with SvTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => ConfigTransforms.noDevNet(config))
      .withTrafficTopupsEnabled
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[TopupMemberTrafficTrigger]
            .withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )
      .withTrafficBalanceCacheDisabled
  }

  "A validator wallet" should {

    "not be able to accept a token standard transfer when its traffic is below the reserve" in {
      implicit env =>
        actAndCheck(
          "Advance enough rounds for SV1 to claim rewards", {
            (0 to 3).foreach { _ =>
              advanceTimeForRewardAutomationToRunForCurrentRound
              eventually() {
                ensureSvRewardCouponReceivedForCurrentRound(sv1ScanBackend, sv1WalletClient)
              }
              advanceRoundsToNextRoundOpening
            }
          },
        )(
          "Wait for SV rewards to be collected",
          _ => sv1WalletClient.balance().unlockedQty should be > BigDecimal(0),
        )

        val now = env.environment.clock.now

        clue("Precondition: aliceValidator cannot afford one top-up") {
          val topupAmount = getTopupParameters(aliceValidatorBackend, now).topupAmount
          val (_, topupCostCc) = computeSynchronizerFees(topupAmount)
          aliceValidatorWalletClient.balance().unlockedQty should be < topupCostCc
        }

        clue("Precondition: aliceValidator's traffic is at or below the reserve") {
          val reserved = aliceValidatorBackend.config.domains.global.reservedTraffic.value
          val remainder =
            getTrafficState(aliceValidatorBackend, activeSynchronizerId).extraTrafficRemainder
          remainder should be <= reserved
        }

        val balanceBefore = aliceValidatorWalletClient.balance().unlockedQty

        val (response, _) = actAndCheck(
          "sv1 creates a token standard transfer to aliceValidator",
          sv1WalletClient.createTokenStandardTransfer(
            aliceValidatorBackend.getValidatorPartyId(),
            BigDecimal(100),
            "low priority test",
            now.plus(Duration.ofMinutes(10)),
            UUID.randomUUID().toString,
          ),
        )(
          "the transfer shows up in aliceValidator's wallet",
          _ => aliceValidatorWalletClient.listTokenStandardTransfers() should have size 1,
        )

        val transferCid = response.output match {
          case members.TransferInstructionPending(value) =>
            new TransferInstruction.ContractId(value.transferInstructionCid)
          case _ => fail("The transfer was expected to be pending.")
        }

        clue("aliceValidator cannot accept it: the handler uses Low priority") {
          assertThrowsAndLogsCommandFailures(
            aliceValidatorWalletClient.acceptTokenStandardTransfer(transferCid),
            entry => entry.message should include("Traffic balance below reserved traffic amount"),
          )
        }

        clue("Nothing changed: the transfer is still pending and the balance is the same") {
          aliceValidatorWalletClient.listTokenStandardTransfers() should have size 1
          aliceValidatorWalletClient.balance().unlockedQty should be(balanceBefore)
        }
    }
  }
}

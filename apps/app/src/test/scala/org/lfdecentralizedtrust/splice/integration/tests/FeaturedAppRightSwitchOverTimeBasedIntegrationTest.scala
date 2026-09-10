package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  holdingv1,
  metadatav1,
  transferinstructionv1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRulesConfig,
  DsoRules_SetConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_SetConfig
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  SwitchOverTimes,
  TimeTestUtil,
  WalletTestUtil,
}

import java.time.Duration
import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_29
class FeaturedAppRightSwitchOverTimeBasedIntegrationTest
    extends IntegrationTest
    with TimeTestUtil
    with WalletTestUtil {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialSvOperationsSwitchOverTimes = None)
        )(config)
      )

  "scan stops serving the featured app right in transfer contexts at the switch-over time" in {
    implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

      clue("The DSO bootstraps without any switch-over times") {
        sv1ScanBackend
          .getDsoInfo()
          .dsoRules
          .payload
          .config
          .svOperationsSwitchOverTimes
          .toScala
          .map(_.asScala.toMap) shouldBe None
      }

      aliceValidatorWalletClient.tap(50.0)
      createTransferPreapprovalEnsuringItExists(aliceValidatorWalletClient, aliceValidatorBackend)
      grantFeaturedAppRight(aliceValidatorWalletClient)
      val featuredAppRight = sv1ScanBackend.lookupFeaturedAppRight(aliceValidatorParty).value

      clue("Without a switch-over time the transfer context contains the featured app right") {
        transferContext(aliceUserParty, aliceValidatorParty).values.asScala
          .get("featured-app-right")
          .value shouldBe new metadatav1.anyvalue.AV_ContractId(
          new metadatav1.AnyContract.ContractId(featuredAppRight.contractId.contractId)
        )
      }

      val switchOverTime = CantonTimestamp.assertFromInstant(
        getLedgerTime.toInstant.plus(Duration.ofMinutes(10))
      )
      actAndCheck(
        "Vote in a switch-over time in the future",
        setNoFeaturedAppChoiceContextSwitchOverTime(switchOverTime),
      )(
        "Scan observes the new DsoRules config",
        _ =>
          sv1ScanBackend
            .getDsoInfo()
            .dsoRules
            .payload
            .config
            .svOperationsSwitchOverTimes
            .toScala
            .map(_.asScala.toMap) shouldBe Some(
            Map(SwitchOverTimes.NoFeaturedAppChoiceContext -> switchOverTime.toInstant)
          ),
      )

      clue("Before the switch-over time the featured app right is still included") {
        transferContext(aliceUserParty, aliceValidatorParty).values.asScala
          .get("featured-app-right")
          .value shouldBe new metadatav1.anyvalue.AV_ContractId(
          new metadatav1.AnyContract.ContractId(featuredAppRight.contractId.contractId)
        )
      }

      advanceTime(Duration.ofMinutes(11))

      clue("After the switch-over time the featured app right is no longer included") {
        transferContext(
          aliceUserParty,
          aliceValidatorParty,
        ).values.asScala.keySet shouldNot contain("featured-app-right")
      }
  }

  private def transferContext(sender: PartyId, receiver: PartyId)(implicit
      env: SpliceTestConsoleEnvironment
  ): metadatav1.ChoiceContext = {
    val now = getLedgerTime.toInstant
    val choiceArgs = new transferinstructionv1.TransferFactory_Transfer(
      dsoParty.toProtoPrimitive,
      new transferinstructionv1.Transfer(
        sender.toProtoPrimitive,
        receiver.toProtoPrimitive,
        BigDecimal(10).bigDecimal,
        new holdingv1.InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
        now,
        now.plus(Duration.ofMinutes(10)),
        // The holdings are irrelevant for the choice context served by scan.
        Seq.empty[holdingv1.Holding.ContractId].asJava,
        ChoiceContextWithDisclosures.emptyMetadata,
      ),
      ChoiceContextWithDisclosures.emptyExtraArgs,
    )
    val (factory, _) = sv1ScanBackend.getTransferFactory(choiceArgs)
    factory.args.extraArgs.context
  }

  private def setNoFeaturedAppChoiceContextSwitchOverTime(
      switchOverTime: CantonTimestamp
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val config = sv1Backend.getDsoInfo().dsoRules.payload.config
    val newConfig = new DsoRulesConfig(
      config.numUnclaimedRewardsThreshold,
      config.numMemberTrafficContractsThreshold,
      config.actionConfirmationTimeout,
      config.svOnboardingRequestTimeout,
      config.svOnboardingConfirmedTimeout,
      config.voteRequestTimeout,
      config.dsoDelegateInactiveTimeout,
      config.synchronizerNodeConfigLimits,
      config.maxTextLength,
      config.decentralizedSynchronizer,
      config.nextScheduledSynchronizerUpgrade,
      config.voteCooldownTime,
      config.nextScheduledLogicalSynchronizerUpgrade,
      Optional.of(
        Map(SwitchOverTimes.NoFeaturedAppChoiceContext -> switchOverTime.toInstant).asJava
      ),
    )
    sv1Backend.createVoteRequest(
      sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
      new ARC_DsoRules(new SRARC_SetConfig(new DsoRules_SetConfig(newConfig, Optional.empty()))),
      "url",
      "set the no-featured-app-choice-context switch-over time",
      config.voteRequestTimeout,
      None,
    )
  }
}

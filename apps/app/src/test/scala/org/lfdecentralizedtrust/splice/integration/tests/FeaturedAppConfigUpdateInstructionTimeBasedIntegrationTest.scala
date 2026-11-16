package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  FeaturedAppConfig,
  FeaturedAppRight,
  FeaturedAppRight_UpdateConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_CreateFeaturedAppConfigUpdateInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_CreateFeaturedAppConfigUpdateInstruction
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  ExecuteFeaturedAppConfigUpdateInstructionTrigger,
  ExpireFeaturedAppConfigUpdateInstructionTrigger,
}
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}

import scala.jdk.OptionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25
class FeaturedAppConfigUpdateInstructionTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[ExecuteFeaturedAppConfigUpdateInstructionTrigger]
            .withPausedTrigger[ExpireFeaturedAppConfigUpdateInstructionTrigger]
        )(config)
      )

  "ExecuteFeaturedAppConfigUpdateInstructionTrigger applies the voted config update" in {
    implicit env =>
      val providerParty = setupProviderWithFeaturedAppRight()
      val newWeight = new java.math.BigDecimal(2)

      clue("before the vote, the provider's featured app right has no activity weight set") {
        lookupActivityWeight(providerParty) shouldBe None
      }

      actAndCheck(
        "SV votes to update the provider's featured app config",
        voteToUpdateFeaturedAppConfig(providerParty, newWeight),
      )(
        "the config update instruction is created",
        _ =>
          sv1Backend.appState.dsoStore
            .listFeaturedAppConfigUpdateInstructionSample()
            .futureValue should not be empty,
      )

      actAndCheck(
        "the execute trigger runs",
        sv1Backend.dsoDelegateBasedAutomation
          .trigger[ExecuteFeaturedAppConfigUpdateInstructionTrigger]
          .runOnce()
          .futureValue,
      )(
        "scan lists the featured app right with the updated weight and the instruction is archived",
        _ => {
          lookupActivityWeight(providerParty).exists(_.compareTo(newWeight) == 0) shouldBe true
          sv1Backend.appState.dsoStore
            .listFeaturedAppConfigUpdateInstructionSample()
            .futureValue shouldBe empty
        },
      )
  }

  "ExpireFeaturedAppConfigUpdateInstructionTrigger expires an unexecuted instruction" in {
    implicit env =>
      val providerParty = setupProviderWithFeaturedAppRight()
      val newWeight = new java.math.BigDecimal(2)
      val voteRequestTimeout =
        sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout

      actAndCheck(
        "SV votes to update the provider's featured app config",
        voteToUpdateFeaturedAppConfig(providerParty, newWeight),
      )(
        "the config update instruction is created",
        _ =>
          sv1Backend.appState.dsoStore
            .listFeaturedAppConfigUpdateInstructionSample()
            .futureValue should not be empty,
      )

      // The execute trigger is paused, so the instruction is never applied and lives until expiry.
      // The provider keeps its right: expiry is unconditional.
      advanceTime(
        java.time.Duration.ofNanos(voteRequestTimeout.microseconds * 1000).plusMinutes(1)
      )

      actAndCheck(
        "the expire trigger runs",
        sv1Backend.dsoDelegateBasedAutomation
          .trigger[ExpireFeaturedAppConfigUpdateInstructionTrigger]
          .runOnce()
          .futureValue,
      )(
        "the instruction is archived and the featured app right is unchanged",
        _ => {
          sv1Backend.appState.dsoStore
            .listFeaturedAppConfigUpdateInstructionSample()
            .futureValue shouldBe empty
          sv1ScanBackend.lookupFeaturedAppRight(providerParty) should not be empty
          lookupActivityWeight(providerParty) shouldBe None
        },
      )
  }

  private def lookupActivityWeight(providerParty: PartyId)(implicit
      env: SpliceTestConsoleEnvironment
  ): Option[java.math.BigDecimal] =
    sv1ScanBackend
      .lookupFeaturedAppRight(providerParty)
      .flatMap(_.payload.activityWeight.toScala)

  private def setupProviderWithFeaturedAppRight()(implicit
      env: SpliceTestConsoleEnvironment
  ): PartyId = {
    val providerParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    actAndCheck(
      "DSO creates the provider's FeaturedAppRight",
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(dsoParty),
          readAs = Seq.empty,
          update = new FeaturedAppRight(
            dsoParty.toProtoPrimitive,
            providerParty.toProtoPrimitive,
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
          ).create,
        ),
    )(
      "the provider's featured app right is visible in scan",
      _ => sv1ScanBackend.lookupFeaturedAppRight(providerParty) should not be empty,
    )
    providerParty
  }

  private def voteToUpdateFeaturedAppConfig(
      providerParty: PartyId,
      newActivityWeight: java.math.BigDecimal,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val baseConfig = new FeaturedAppConfig(
      java.util.Optional.empty(),
      java.util.Optional.empty(),
      java.util.Optional.empty(),
    )
    val newConfig = new FeaturedAppConfig(
      java.util.Optional.empty(),
      java.util.Optional.of(newActivityWeight),
      java.util.Optional.empty(),
    )
    val action = new ARC_DsoRules(
      new SRARC_CreateFeaturedAppConfigUpdateInstruction(
        new DsoRules_CreateFeaturedAppConfigUpdateInstruction(
          java.util.List.of(
            new Tuple2(
              providerParty.toProtoPrimitive,
              new FeaturedAppRight_UpdateConfig(baseConfig, newConfig),
            )
          )
        )
      )
    )
    sv1Backend.createVoteRequest(
      sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
      action,
      "url",
      "update featured app config",
      sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
      None,
    )
  }
}

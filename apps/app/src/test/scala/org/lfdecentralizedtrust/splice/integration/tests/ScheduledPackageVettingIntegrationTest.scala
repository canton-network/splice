package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.LfPackageId
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.PackageConfig
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.InitialPackageConfig
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  AmuletConfigUtil,
  PackageUnvettingUtil,
}
import org.lfdecentralizedtrust.splice.validator.automation.ValidatorPackageVettingTrigger
import org.scalatest.concurrent.PatienceConfiguration

import java.time.Duration
import scala.concurrent.duration.FiniteDuration

@org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck
class ScheduledPackageVettingIntegrationTest
    extends IntegrationTest
    with PackageUnvettingUtil
    with AmuletConfigUtil {

  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withoutAliceValidatorConnectingToSplitwell
      .withNoVettedPackages(implicit env => env.validators.local.map(_.participantClient))
      .withReducedAmuletRulesCacheTTL()
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(
            initialPackageConfig = InitialPackageConfig(
              amuletVersion = DarResources.amulet_0_1_19.metadata.version.toString,
              amuletNameServiceVersion =
                DarResources.amuletNameService_0_1_20.metadata.version.toString,
              dsoGovernanceVersion = DarResources.dsoGovernance_0_1_25.metadata.version.toString,
              validatorLifecycleVersion =
                InitialPackageConfig.defaultInitialPackageConfig.validatorLifecycleVersion,
              walletVersion = DarResources.wallet_0_1_20.metadata.version.toString,
              walletPaymentsVersion = DarResources.walletPayments_0_1_19.metadata.version.toString,
            ),
            initialSvOperationsSwitchOverTimes = None,
          )
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs { case (name, c) =>
          if (name == "aliceValidator")
            c.copy(automation = c.automation.withPausedTrigger[ValidatorPackageVettingTrigger])
          else c
        }(config)
      )

  "Package versions of an accepted, not yet effective config stay vetted after a validator restart" in {
    implicit env =>
      val synchronizerId =
        sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId

      def aliceParticipant = aliceValidatorBackend.appState.participantAdminConnection

      def aliceVettingTrigger =
        aliceValidatorBackend.validatorAutomation.trigger[ValidatorPackageVettingTrigger]

      val scheduledValidatorDars = Seq(
        DarResources.amulet_0_1_20,
        DarResources.amuletNameService_0_1_21,
        DarResources.wallet_0_1_21,
        DarResources.walletPayments_0_1_20,
      )

      def scheduledDarsAreVettedFromEffectiveTime(): Unit = {
        val effectiveAt = CantonTimestamp.assertFromInstant(
          sv1Backend.listVoteRequests().loneElement.payload.targetEffectiveAt.get
        )
        val validFrom = getVettedPackagesValidFrom(aliceParticipant, synchronizerId)
        forAll(scheduledValidatorDars) { dar =>
          validFrom.get(LfPackageId.assertFromString(dar.packageId)) shouldBe Some(
            Some(effectiveAt)
          )
        }
      }

      aliceVettingTrigger.resume()

      actAndCheck(
        "sv1 schedules newer package versions to become effective in one hour", {
          val config = AmuletConfigSchedule(sv1ScanBackend.getAmuletRules())
            .getConfigAsOf(env.environment.clock.now)
          val newerPackageConfig = new PackageConfig(
            DarResources.amulet_0_1_20.metadata.version.toString(),
            DarResources.amuletNameService_0_1_21.metadata.version.toString(),
            DarResources.dsoGovernance_0_1_26.metadata.version.toString(),
            config.packageConfig.validatorLifecycle,
            DarResources.wallet_0_1_21.metadata.version.toString(),
            DarResources.walletPayments_0_1_20.metadata.version.toString(),
          )
          setAmuletConfig(
            Seq((Some(Duration.ofHours(1)), withPackageConfig(config, newerPackageConfig), config))
          )
        },
      )(
        "alice's validator vets the scheduled package versions from their effective time",
        _ => scheduledDarsAreVettedFromEffectiveTime(),
      )

      clue("alice's validator restarts and its vetting trigger runs once") {
        aliceValidatorBackend.stop()
        aliceValidatorBackend.startSync()
        aliceVettingTrigger
          .runOnce()
          .futureValue(timeout = PatienceConfiguration.Timeout(FiniteDuration(60, "seconds")))
      }

      clue("the scheduled package versions are still vetted from their effective time") {
        scheduledDarsAreVettedFromEffectiveTime()
      }
  }
}

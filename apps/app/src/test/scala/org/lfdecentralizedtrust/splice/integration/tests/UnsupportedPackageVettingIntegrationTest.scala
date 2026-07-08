// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.SynchronizerId
import org.lfdecentralizedtrust.splice.automation.PackageVettingTrigger
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  PackageConfig,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.{
  DarResource,
  DarResources,
  ParticipantAdminConnection,
  RetryFor,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SvPackageVettingTrigger
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  AmuletConfigUtil,
  PackageUnvettingUtil,
  UploadablePackage,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllValidatorConfigs
import org.lfdecentralizedtrust.splice.validator.automation.ValidatorPackageVettingTrigger
import org.scalatest.concurrent.PatienceConfiguration

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

class UnsupportedPackageVettingIntegrationTest
    extends IntegrationTest
    with PackageUnvettingUtil
    with AmuletConfigUtil
    with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withoutAliceValidatorConnectingToSplitwell
      // if other tests run before, packages that break this test might already be vetted
      .withNoVettedPackages(implicit env => env.validators.local.map(_.participantClient))
      .withReducedAmuletRulesCacheTTL()
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[SvPackageVettingTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAllValidatorConfigs { case (name, c) =>
          if (name == "aliceValidator") {
            c.copy(
              automation = c.automation.withPausedTrigger[ValidatorPackageVettingTrigger]
            )
          } else c
        }(config)
      )

  "Unsupported vetted packages are automatically removed by the package vetting trigger for SV and validator" in {
    implicit env =>
      val unsupportedDarsToVetSv = Seq(
        DarResources.dsoGovernance_0_1_0,
        DarResources.walletPayments_0_1_0,
        DarResources.amuletNameService_0_1_0,
        DarResources.amulet_0_1_0,
      )
      val unsupportedDarsToVetValidator = Seq(
        DarResources.walletPayments_0_1_0,
        DarResources.amuletNameService_0_1_0,
        DarResources.amulet_0_1_0,
        // Vet some non-sense dar to catch potential issues from that
        // see e.g. https://github.com/DACH-NY/cn-test-failures/issues/8034.
        DarResources.dsoGovernance_0_1_0,
      )
      val synchronizerId =
        sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId
      test(
        sv1Backend.appState.participantAdminConnection,
        synchronizerId,
        unsupportedDarsToVetSv,
        unsupportedDarsToVetSv,
        sv1Backend.dsoAutomation.trigger[SvPackageVettingTrigger],
      )
      test(
        aliceValidatorBackend.appState.participantAdminConnection,
        synchronizerId,
        unsupportedDarsToVetValidator,
        unsupportedDarsToVetValidator,
        aliceValidatorBackend.validatorAutomation.trigger[ValidatorPackageVettingTrigger],
      )
  }

  private def test(
      participantAdminConnection: ParticipantAdminConnection,
      synchronizerId: SynchronizerId,
      unsupportedDarsToVet: Seq[DarResource],
      darsUnvettedByAutomation: Seq[DarResource],
      vettingTrigger: PackageVettingTrigger,
  ) = {
    val participantId = participantAdminConnection.getParticipantId().futureValue
    val name = participantId.uid.identifier
    actAndCheck(
      s"$name uploads and vets unsupported packages", {
        participantAdminConnection
          .uploadDarFiles(
            unsupportedDarsToVet.map(UploadablePackage.fromResource),
            RetryFor.Automation,
          )
          .futureValue
        participantAdminConnection
          .vetDars(synchronizerId, unsupportedDarsToVet, None, None)
          .futureValue(timeout = PatienceConfiguration.Timeout(FiniteDuration(40, "seconds")))
      },
    )(
      s"the unsupported packages are vetted on $name",
      _ =>
        getVettedPackageIds(
          participantAdminConnection,
          synchronizerId,
        ) should contain allElementsOf unsupportedDarsToVet.map(_.packageId),
    )
    clue(s"the unsupported packages are then removed by the package vetting trigger from $name") {
      vettingTrigger.resume()
      eventually() {
        getVettedPackageIds(
          participantAdminConnection,
          synchronizerId,
        ) should contain noElementsOf darsUnvettedByAutomation.map(_.packageId)
      }
    }
  }

  "SVs and validators unvet package versions above the configured PackageConfig" in {
    implicit env =>
      val synchronizerId =
        sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId

      val validatorDarsAbovePackageConfigVersion = Seq(
        DarResources.wallet_0_1_18,
        DarResources.walletPayments_0_1_17,
        DarResources.amuletNameService_0_1_18,
        DarResources.amulet_0_1_17,
      )
      val svDarsAbovePackageConfigVersion = Seq(
        DarResources.dsoGovernance_0_1_23
      ) ++ validatorDarsAbovePackageConfigVersion

      clue("sv1 votes to downgrade to the previous package versions") {
        val amuletRules = sv1ScanBackend.getAmuletRules()
        val currentConfig =
          AmuletConfigSchedule(amuletRules).getConfigAsOf(env.environment.clock.now)

        val downgradedPackageConfig = new PackageConfig(
          DarResources.amulet_0_1_16.metadata.version.toString(),
          DarResources.amuletNameService_0_1_17.metadata.version.toString(),
          DarResources.dsoGovernance_0_1_22.metadata.version.toString(),
          currentConfig.packageConfig.validatorLifecycle,
          DarResources.wallet_0_1_17.metadata.version.toString(),
          DarResources.walletPayments_0_1_16.metadata.version.toString(),
        )
        val newAmuletConfig = new AmuletConfig(
          currentConfig.transferConfig,
          currentConfig.issuanceCurve,
          currentConfig.decentralizedSynchronizer,
          currentConfig.tickDuration,
          downgradedPackageConfig,
          currentConfig.transferPreapprovalFee,
          currentConfig.featuredAppActivityMarkerAmount,
          currentConfig.optDevelopmentFundManager,
          currentConfig.externalPartyConfigStateTickDuration,
          currentConfig.rewardConfig,
          currentConfig.transferPreapprovalBaseDuration,
        )
        setAmuletConfig(Seq((None, newAmuletConfig, currentConfig)))
      }

      clue("sv1 unvets package versions above the downgraded PackageConfig") {
        eventually() {
          getVettedPackageIds(
            sv1Backend.appState.participantAdminConnection,
            synchronizerId,
          ) should contain noElementsOf svDarsAbovePackageConfigVersion.map(_.packageId)
        }
      }

      clue("sv1 validator unvets package versions above the downgraded PackageConfig") {
        eventually() {
          getVettedPackageIds(
            sv1ValidatorBackend.appState.participantAdminConnection,
            synchronizerId,
          ) should contain noElementsOf validatorDarsAbovePackageConfigVersion.map(_.packageId)
        }
      }

      clue("alice validator unvets package versions above the downgraded PackageConfig") {
        eventually() {
          getVettedPackageIds(
            aliceValidatorBackend.appState.participantAdminConnection,
            synchronizerId,
          ) should contain noElementsOf validatorDarsAbovePackageConfigVersion.map(_.packageId)
        }
        eventually(40.seconds) {
          alicesTapsWithPackageId(DarResources.amulet_0_1_16.packageId)
        }
      }
  }

  "Unvetting amulet on the SV does not affect a validator that has splitwell depending on it" in {
    implicit env =>
      val aliceValidatorVettingTrigger =
        aliceValidatorBackend.validatorAutomation.trigger[ValidatorPackageVettingTrigger]

      val synchronizerId =
        sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId
      val aliceParticipant = aliceValidatorBackend.appState.participantAdminConnection

      val splitwellDar = DarResources.splitwell_0_1_0
      val amuletDependency = DarResources.amulet_0_1_0

      actAndCheck(
        "alice uploads and vets splitwell_0_1_0 (which vets amulet_0_1_0 as a dependency)", {
          aliceParticipant
            .uploadDarFiles(
              Seq(splitwellDar).map(UploadablePackage.fromResource),
              RetryFor.Automation,
            )
            .futureValue
          aliceParticipant
            .vetDars(synchronizerId, Seq(splitwellDar), None, None)
            .futureValue(timeout = PatienceConfiguration.Timeout(FiniteDuration(40, "seconds")))
        },
      )(
        "both splitwell_0_1_0 and amulet_0_1_0 are vetted on alice's participant",
        _ => {
          val vettedIds = getVettedPackageIds(aliceParticipant, synchronizerId)
          vettedIds should contain(splitwellDar.packageId)
          vettedIds should contain(amuletDependency.packageId)
        },
      )

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        aliceValidatorVettingTrigger.resume(),
        entries => {
          forAtLeast(1, entries)(
            _.message should include("Not running package unvetting")
          )
        },
      )

      clue("splitwell_0_1_0 and amulet_0_1_0 remain vetted after trigger ran") {
        val vettedIds = getVettedPackageIds(aliceParticipant, synchronizerId)
        vettedIds should contain(splitwellDar.packageId)
        vettedIds should contain(amuletDependency.packageId)
      }

  }
}

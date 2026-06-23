// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import monocle.macros.syntax.lens.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  PackageConfig,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.InitialPackageConfig
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  AmuletConfigUtil,
  PackageUnvettingUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.validator.automation.ValidatorPackageVettingTrigger

import scala.concurrent.duration.DurationInt

@org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck
class ValidatorSkipsEagerVettingOnRestartIntegrationTest
    extends IntegrationTest
    with PackageUnvettingUtil
    with AmuletConfigUtil
    with WalletTestUtil {

  private val initialPackageConfig = InitialPackageConfig.minimumInitialPackageConfig

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withoutAliceValidatorConnectingToSplitwell
      // if other tests run before, packages that break this test might already be vetted
      .withNoVettedPackages(implicit env => env.validators.local.map(_.participantClient))
      // Boot the network on the minimum package version so there is a strictly newer version to
      // vote in later.
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialPackageConfig = initialPackageConfig)
        )(config)
      )
      // Reduce the scan cache TTL so the (later resumed) vetting trigger sees the new AmuletRules
      // quickly rather than waiting out the default TTL.
      .addConfigTransform((_, conf) =>
        ConfigTransforms.updateAllValidatorAppConfigs_(c =>
          c.copy(scanClient =
            c.scanClient.setAmuletRulesCacheTimeToLive(NonNegativeFiniteDuration.ofSeconds(1))
          )
        )(conf)
      )
      // Start the validator vetting trigger paused. Because this lives in the HOCON automation
      // config it survives the stop()/startSync() below, so after the restart nothing other than
      // the eager startup vetting could vet the new version.
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs((name, conf) =>
          if (name == "aliceValidatorBackend") {
            conf.focus(_.automation).modify(_.withPausedTrigger[ValidatorPackageVettingTrigger])
          } else {
            conf
          }
        )(config)
      )

  "Validator skips eager package vetting on restart once it has been initialized" in {
    implicit env =>
      val synchronizerId =
        sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId

      val oldAmuletPackageId =
        DarResources.amulet.getPackageIdWithVersion(initialPackageConfig.amuletVersion).value
      val newAmuletPackageId = DarResources.amulet.latest.packageId
      // Guard the test's premise: there must actually be a newer version to vet.
      newAmuletPackageId should not be oldAmuletPackageId

      // The trigger is paused, so the eager startup vetting is the only thing that could have vetted
      // anything on the first init. Seeing the initial version vetted proves the eager path ran.
      clue("on first init the validator eagerly vetted the initial package version") {
        eventually() {
          getVettedPackageIds(
            aliceValidatorBackend.appState.participantAdminConnection,
            synchronizerId,
          ) should contain(oldAmuletPackageId)
        }
        getVettedPackageIds(
          aliceValidatorBackend.appState.participantAdminConnection,
          synchronizerId,
        ) should contain noElementsOf Seq(newAmuletPackageId)
      }

      aliceValidatorBackend.stop()

      clue("vote the package config up to the latest versions while the validator is stopped") {
        val amuletRules = sv1ScanBackend.getAmuletRules()
        val currentConfig =
          AmuletConfigSchedule(amuletRules).getConfigAsOf(env.environment.clock.now)
        val newPackageConfig = new PackageConfig(
          DarResources.amulet.latest.metadata.version.toString(),
          DarResources.amuletNameService.latest.metadata.version.toString(),
          DarResources.dsoGovernance.latest.metadata.version.toString(),
          DarResources.validatorLifecycle.latest.metadata.version.toString(),
          DarResources.wallet.latest.metadata.version.toString(),
          DarResources.walletPayments.latest.metadata.version.toString(),
        )
        val newAmuletConfig = new AmuletConfig(
          currentConfig.transferConfig,
          currentConfig.issuanceCurve,
          currentConfig.decentralizedSynchronizer,
          currentConfig.tickDuration,
          newPackageConfig,
          currentConfig.transferPreapprovalFee,
          currentConfig.featuredAppActivityMarkerAmount,
          currentConfig.optDevelopmentFundManager,
          currentConfig.externalPartyConfigStateTickDuration,
          currentConfig.rewardConfig,
          currentConfig.transferPreapprovalBaseDuration,
        )
        setAmuletConfig(Seq((None, newAmuletConfig, currentConfig)))
        eventually() {
          sv1Backend
            .getDsoInfo()
            .amuletRules
            .payload
            .configSchedule
            .initialValue
            .packageConfig
            .amulet shouldBe
            DarResources.amulet.latest.metadata.version.toString()
        }
      }

      aliceValidatorBackend.startSync()

      // startSync() returns only after init (and thus the eager vetting, had it run) completed, and
      // the trigger is paused, so the vetting state is stable: the new version must be absent.
      clue("after restart the newly-required version was NOT eagerly vetted") {
        always(durationOfSuccess = 5.seconds) {
          getVettedPackageIds(
            aliceValidatorBackend.appState.participantAdminConnection,
            synchronizerId,
          ) should contain noElementsOf Seq(newAmuletPackageId)
        }
        // The initial version is still vetted from the first init.
        getVettedPackageIds(
          aliceValidatorBackend.appState.participantAdminConnection,
          synchronizerId,
        ) should contain(oldAmuletPackageId)
      }

      clue("resuming the vetting trigger vets the new version (confirms it was vettable)") {
        aliceValidatorBackend.validatorAutomation.trigger[ValidatorPackageVettingTrigger].resume()
        eventually() {
          getVettedPackageIds(
            aliceValidatorBackend.appState.participantAdminConnection,
            synchronizerId,
          ) should contain(newAmuletPackageId)
        }
      }
  }
}

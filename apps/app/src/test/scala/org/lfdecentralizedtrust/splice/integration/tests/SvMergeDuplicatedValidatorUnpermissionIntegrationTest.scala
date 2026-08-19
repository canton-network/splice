// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.Identifier
import com.digitalasset.canton.logging.SuppressionRule
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorUnpermission
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.MergeValidatorUnpermissionContractsTrigger
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil.{
  pauseAllDsoDelegateTriggers,
  resumeAllDsoDelegateTriggers,
}
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*

class SvMergeDuplicatedValidatorUnpermissionIntegrationTest
    extends SvIntegrationTestBase
    with TriggerTestUtil {

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[MergeValidatorUnpermissionContractsTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs { case (_, c) =>
          c.copy(permissionedSynchronizer = true)
        }(config)
      )

  override protected lazy val sanityChecksIgnoredRootCreates: Seq[Identifier] = Seq(
    ValidatorUnpermission.TEMPLATE_ID_WITH_PACKAGE_ID
  )

  "Duplicated ValidatorUnpermissions for the same validator and participant get merged" in {
    implicit env =>
      val dso = sv1Backend.getDsoInfo().dsoParty
      val aliceValidator = aliceValidatorBackend.getValidatorPartyId()
      val svParty = sv1Backend.getDsoInfo().svParty

      def getValidatorUnpermissions() =
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(ValidatorUnpermission.COMPANION)(
            dso,
            _ => true,
          )

      val aliceParticipant1 = "participant1s"
      val aliceParticipant2 = "participant2"

      val create1 = new ValidatorUnpermission(
        dso.toProtoPrimitive,
        aliceValidator.toProtoPrimitive,
        aliceParticipant1,
        java.util.Optional.empty(),
        java.lang.Boolean.FALSE,
      ).create()
      val create2 = new ValidatorUnpermission(
        dso.toProtoPrimitive,
        aliceValidator.toProtoPrimitive,
        aliceParticipant1,
        java.util.Optional.empty(),
        java.lang.Boolean.TRUE,
      ).create()
      val create3 = new ValidatorUnpermission(
        dso.toProtoPrimitive,
        svParty.toProtoPrimitive,
        aliceParticipant1,
        java.util.Optional.empty(),
        java.lang.Boolean.FALSE,
      ).create()
      val create4 = new ValidatorUnpermission(
        dso.toProtoPrimitive,
        aliceValidator.toProtoPrimitive,
        aliceParticipant2,
        java.util.Optional.empty(),
        java.lang.Boolean.FALSE,
      ).create()

      actAndCheck(
        "Create 4 ValidatorUnpermission contracts",
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
          Seq(dso),
          commands = Seq(create1, create2, create3, create4).flatMap(_.commands.asScala),
        ),
      )(
        "4 validator unpermissions get created",
        _ => {
          val unpermissions = getValidatorUnpermissions()
          unpermissions should have size 4 withClue "has 4 ValidatorUnpermissions"
        },
      )

      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        {
          resumeAllDsoDelegateTriggers[MergeValidatorUnpermissionContractsTrigger]
          clue("Trigger merges the duplicated validator unpermission contracts") {
            eventually() {
              val unpermissions = getValidatorUnpermissions()
              unpermissions should have size 3 withClue "has 3 ValidatorUnpermissions"
            }
          }
          pauseAllDsoDelegateTriggers[MergeValidatorUnpermissionContractsTrigger]
        },
        forAll(_)(
          _.warningMessage should include(
            s"has 2 ValidatorUnpermission contracts"
          )
        ),
      )
  }

}

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{FrontendLoginUtil, SvFrontendTestUtil, SvTestUtil}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.SynchronizerPermissionState

class PermissionedSynchronizerSvFrontendIntegrationTest
    extends SvFrontendCommonIntegrationTest
    with SvTestUtil
    with SvFrontendTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs { case (_, c) =>
          c.copy(permissionedSynchronizer = true)
        }(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs { case (_, c) =>
          c.copy(permissionedSynchronizer = true)
        }(config)
      )
      .withManualStart

  "Permissioned Validator Onboarding UI" should {
    "allow an SV sponser to grant a ValidatorPermission" in { implicit env =>
      initDsoWithSv1Only()

      val testPartyId = "PermissionedParty::12201ff69b"
      val testParticipantId = "PAR::PermissionedParticipant::1220a4d7467"

      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "sv1 operator can login and browse to the validator onboarding tab", {
            go to s"http://localhost:$sv1UIPort/validator-onboarding"
            loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
          },
        )(
          "Validator permissioning form exists",
          _ => {
            find(
              id("validator-party-id")
            ) should not be empty withClue "'Validator Party ID' textfield"
            find(
              id("validator-participant-id")
            ) should not be empty withClue "'Validator Participant ID' textfield"
            find(
              id("grant-validator-permission-btn")
            ) should not be empty withClue "'Grant Permission' button"
          },
        )

        actAndCheck(
          "fill the party and participant fields and click on the grant permission button", {
            clue("fill party ID") {
              inside(find(id("validator-party-id"))) { case Some(element) =>
                element.underlying.sendKeys(testPartyId)
              }
            }

            clue("fill participant ID") {
              inside(find(id("validator-participant-id"))) { case Some(element) =>
                element.underlying.sendKeys(testParticipantId)
              }
            }

            clue("wait for the grant button to become enabled (regex validation passed)") {
              eventually() {
                find(id("grant-validator-permission-btn")).value.isEnabled shouldBe true
              }
            }

            clue("click the grant permission button") {
              eventuallyClickOn(id("grant-validator-permission-btn"))
            }
          },
        )(
          "a new ParticipantSynchronizerPermission is created",
          _ => {
            eventually() {
              eventually() {
                sv1ScanBackend.getParticipantSynchronizerPermission(
                  decentralizedSynchronizerId.toProtoPrimitive,
                  testParticipantId,
                ) shouldBe Some(
                  SynchronizerPermissionState(None)
                )
              }
            }
          },
        )
      }
    }
  }
}

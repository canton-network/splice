package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.SynchronizerPermissionState
import org.lfdecentralizedtrust.splice.util.*
import org.openqa.selenium.By

class PermissionedSynchronizerSvFrontendIntegrationTest
    extends SvFrontendCommonIntegrationTest
    with SvTestUtil
    with SvFrontendTestUtil
    with FrontendLoginUtil
    with WalletTestUtil {

  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
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

  def fillOutTextField(elementId: String, text: String, chunkSize: Int = 16)(implicit
      webDriver: WebDriverType
  ) = {
    eventually() {
      inside(find(id(elementId))) { case Some(element) =>
        text.grouped(chunkSize).foreach { chunk =>
          element.underlying.sendKeys(chunk)
        }
      }
    }
  }

  def svCastVoteOnActionRequired(
      uiPort: Int,
      backend: org.lfdecentralizedtrust.splice.console.SvAppBackendReference,
      proposalContractId: String,
  )(implicit webDriver: WebDriverType): Unit = {
    actAndCheck(
      s"${backend.name} operator can login and browse to the proposal details page", {
        go to s"http://localhost:$uiPort/governance/proposals/$proposalContractId"
        loginOnCurrentPage(uiPort, backend.config.ledgerApiUser)
      },
    )(
      s"${backend.name} can see the vote form",
      _ => find(cssSelector("[data-testid='your-vote-reason-input']")) should not be empty,
    )

    actAndCheck(
      s"${backend.name} fills out and submits an accept vote", {
        inside(find(cssSelector("[data-testid='your-vote-reason-input']"))) { case Some(element) =>
          element.underlying.sendKeys("A sample reason")
        }
        inside(find(cssSelector("[data-testid='your-vote-url-input']"))) { case Some(element) =>
          element.underlying.sendKeys(
            "https://my-splice-vote-url.com"
          )
        }
        click on cssSelector("[data-testid='your-vote-accept']")
      },
    )(
      "the vote submission success message is shown",
      _ =>
        inside(find(cssSelector("[data-testid='vote-submission-success']"))) { case Some(element) =>
          element.text shouldBe "Vote successfully updated!"
        },
    )
  }

  "SV UIs in permissioned mode" should {
    "create and confirm unpermission proposals" in { implicit env =>
      initDso()

      val aliceParticipantId = aliceValidatorBackend.participantClient.id.toProtoPrimitive
      val bobParticipantId = bobValidatorBackend.participantClient.id.toProtoPrimitive

      clue("Sponsor SV Buys Member Traffic for Alice in the DevNet") {
        sv2Backend.devNetBuyMemberTraffic(aliceValidatorBackend.participantClient.id)
        eventually() {
          sv1ScanBackend.getParticipantSynchronizerPermission(
            decentralizedSynchronizerId.toProtoPrimitive,
            aliceParticipantId,
          ) shouldBe Some(SynchronizerPermissionState(None))
        }
      }

      clue("Sponsor SV Buys Member Traffic for Bob in the DevNet") {
        eventually() {
          sv2Backend.devNetBuyMemberTraffic(bobValidatorBackend.participantClient.id)
          sv1ScanBackend.getParticipantSynchronizerPermission(
            decentralizedSynchronizerId.toProtoPrimitive,
            bobParticipantId,
          ) shouldBe Some(SynchronizerPermissionState(None))
        }
      }

      clue("Alice and Bob Validators start and onboard correctly") {
        aliceValidatorBackend.startSync()
        aliceValidatorBackend.onboardUser("TestUserAlice")

        bobValidatorBackend.startSync()
        bobValidatorBackend.onboardUser("TestUserBob")
      }

      aliceValidatorBackend.stop()
      bobValidatorBackend.stop()

      val loginAfterDate = "2099-01-31 00:12"

      withFrontEnd("sv1") { implicit webDriver =>
        go to s"http://localhost:$sv1UIPort/governance"
        loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)

        click on id("initiate-proposal-button")

        eventually() {
          webDriver.findElement(By.id("select-action")).click()
        }
        eventually() {
          webDriver
            .findElement(By.cssSelector("[data-testid='SRARC_UnpermissionValidator']"))
            .click()
        }
        eventually() {
          click on id("next-button")
        }

        fillOutTextField("unpermission-validator-participant-id", aliceParticipantId)
        setDateTime("sv1", "unpermission-validator-login-after", loginAfterDate)

        inside(find(id("unpermission-validator-summary"))) { case Some(element) =>
          element.underlying.sendKeys("Suspend Alice")
        }
        inside(find(id("unpermission-validator-url"))) { case Some(element) =>
          element.underlying.sendKeys("https://example.com")
        }

        eventually() { webDriver.findElement(By.id("submit-button")).click() }
        eventually() {
          webDriver.findElement(By.id("submit-button")).getText shouldBe "Submit Proposal"
        }
        eventually() { webDriver.findElement(By.id("submit-button")).click() }

        eventually() { find(id("initiate-proposal-button")) should not be empty }
      }

      val aliceVoteRequest = eventually() {
        sv1Backend
          .listVoteRequests()
          .find(req =>
            req.payload.action.toValue.toString.contains("SRARC_UnpermissionValidator") &&
              req.payload.action.toValue.toString.contains(aliceParticipantId)
          )
          .value
      }
      val aliceRequestId = getTrackingId(aliceVoteRequest).contractId

      withFrontEnd("sv2") { implicit webDriver =>
        svCastVoteOnActionRequired(sv2UIPort, sv2Backend, aliceRequestId)
      }

      clue("SV3 accepts the vote request via backend") {
        eventuallySucceeds() {
          sv3Backend.castVote(
            aliceVoteRequest.contractId,
            isAccepted = true,
            "url",
            "description",
          )
        }
      }

      clue("Verify Alice's ParticipantSynchronizerPermission is updated with loginAfter") {
        eventually() {
          val permission = sv1ScanBackend
            .getParticipantSynchronizerPermission(
              decentralizedSynchronizerId.toProtoPrimitive,
              aliceParticipantId,
            )
            .value
          permission.loginAfter.map(_.toInstant) shouldBe Some(
            java.time.Instant.parse("2099-01-31T00:12:00Z")
          )
        }
      }

      withFrontEnd("sv1") { implicit webDriver =>
        go to s"http://localhost:$sv1UIPort/governance/proposals"

        click on id("initiate-proposal-button")

        eventually() {
          webDriver.findElement(By.id("select-action")).click()
        }
        eventually() {
          webDriver
            .findElement(By.cssSelector("[data-testid='SRARC_UnpermissionValidator']"))
            .click()
        }
        eventually() {
          click on id("next-button")
        }

        fillOutTextField("unpermission-validator-participant-id", bobParticipantId)

        eventually() {
          val checkbox = webDriver.findElement(By.id("unpermission-validator-revoked"))
          webDriver
            .asInstanceOf[org.openqa.selenium.JavascriptExecutor]
            .executeScript("arguments[0].click();", checkbox)
        }

        inside(find(id("unpermission-validator-summary"))) { case Some(element) =>
          element.underlying.sendKeys("Revoke Bob")
        }
        inside(find(id("unpermission-validator-url"))) { case Some(element) =>
          element.underlying.sendKeys("https://example.com")
        }

        eventually() { webDriver.findElement(By.id("submit-button")).click() }
        eventually() {
          webDriver.findElement(By.id("submit-button")).getText shouldBe "Submit Proposal"
        }
        eventually() { webDriver.findElement(By.id("submit-button")).click() }

        eventually() { find(id("initiate-proposal-button")) should not be empty }
      }

      val bobVoteRequest = eventually() {
        sv1Backend
          .listVoteRequests()
          .find(req =>
            req.payload.action.toValue.toString.contains("SRARC_UnpermissionValidator") &&
              req.payload.action.toValue.toString.contains(bobParticipantId)
          )
          .value
      }
      val bobRequestId = getTrackingId(bobVoteRequest).contractId

      withFrontEnd("sv2") { implicit webDriver =>
        svCastVoteOnActionRequired(sv2UIPort, sv2Backend, bobRequestId)
      }

      clue("SV3 accepts the vote request via backend") {
        eventuallySucceeds() {
          sv3Backend.castVote(
            bobVoteRequest.contractId,
            isAccepted = true,
            "url",
            "description",
          )
        }
      }

      clue("Verify Bob's ParticipantSynchronizerPermission is completely removed") {
        eventually() {
          sv1ScanBackend.getParticipantSynchronizerPermission(
            decentralizedSynchronizerId.toProtoPrimitive,
            bobParticipantId,
          ) shouldBe None
        }
      }
    }
  }
}

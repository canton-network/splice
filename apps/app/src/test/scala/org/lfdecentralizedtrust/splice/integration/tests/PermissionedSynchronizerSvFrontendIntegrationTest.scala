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
    with WalletTestUtil
    with SynchronizerFeesTestUtil {

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
    "create and confirm unpermission vote proposals" in { implicit env =>
      initDso()

      val aliceParticipantId = aliceValidatorBackend.participantClient.id.toProtoPrimitive
      val bobParticipantId = bobValidatorBackend.participantClient.id.toProtoPrimitive

      val trafficAmount = Math.max(
        sv1ScanBackend
          .getAmuletConfigAsOf(env.environment.clock.now)
          .decentralizedSynchronizer
          .fees
          .minTopupAmount
          .toLong,
        1_000_000L,
      )

      val sv1WalletUserParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
      sv1WalletClient.tap(20000)

      clue(
        s"SV1 buys MemberTraffic for ${aliceValidatorBackend.participantClient.name} && ${bobValidatorBackend.participantClient.name}"
      ) {
        createBuyTrafficRequest(
          validatorApp = sv1ValidatorBackend,
          buyer = sv1WalletUserParty,
          memberId = aliceValidatorBackend.participantClient.id.toProtoPrimitive,
          trafficAmount = trafficAmount,
          trackingId = s"traffic-for-${aliceValidatorBackend.participantClient.name}",
        )
        createBuyTrafficRequest(
          validatorApp = sv1ValidatorBackend,
          buyer = sv1WalletUserParty,
          memberId = bobValidatorBackend.participantClient.id.toProtoPrimitive,
          trafficAmount = trafficAmount,
          trackingId = s"traffic-for-${bobValidatorBackend.participantClient.name}",
        )
      }

      eventually() {
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          aliceParticipantId,
        ) shouldBe Some(SynchronizerPermissionState(None))
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          bobParticipantId,
        ) shouldBe Some(SynchronizerPermissionState(None))
      }

      clue("Alice and Bob Validators start and onboard correctly") {
        aliceValidatorBackend.startSync()
        bobValidatorBackend.startSync()
      }

      aliceValidatorBackend.stop()
      bobValidatorBackend.stop()

      withFrontEnd("sv1") { implicit webDriver =>
        clue("Login to sv-1") {
          go to s"http://localhost:$sv1UIPort/governance"
          loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
        }

        clue("SV1 creates vote request for temporary suspension of Alice") {
          eventuallyClickOn(id("initiate-proposal-button"))

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

          eventually() {
            find(id("unpermission-validator-form")) should not be empty
          }

          fillOutTextField("unpermission-validator-participant-id", aliceParticipantId)

          eventually() {
            val radio = webDriver.findElement(By.cssSelector("input[value='threshold']"))
            webDriver
              .asInstanceOf[org.openqa.selenium.JavascriptExecutor]
              .executeScript("arguments[0].click();", radio)
          }

          inside(find(id("unpermission-validator-summary"))) { case Some(element) =>
            element.underlying.sendKeys("Suspend Alice")
          }

          inside(find(id("unpermission-validator-url"))) { case Some(element) =>
            element.underlying.sendKeys("https://example.com")
          }

          eventually() {
            webDriver.findElement(By.id("submit-button")).click()
          }
          eventually() {
            webDriver.findElement(By.id("submit-button")).getText shouldBe "Submit Proposal"
          }
          eventually() {
            webDriver.findElement(By.id("submit-button")).click()
          }

          eventually() {
            find(id("initiate-proposal-button")) should not be empty
          }
        }
      }

      val aliceVoteRequest = clue("Vote Request for Alice is created") {

        eventually() {
          sv1Backend
            .listVoteRequests()
            .find(req =>
              req.payload.action.toValue.toString.contains("SRARC_UnpermissionValidator") &&
                req.payload.action.toValue.toString.contains(aliceParticipantId)
            )
            .value
        }
      }
      val aliceRequestId = getTrackingId(aliceVoteRequest).contractId

      clue("SV2 Cast Vote using UI") {
        withFrontEnd("sv2") { implicit webDriver =>
          svCastVoteOnActionRequired(sv2UIPort, sv2Backend, aliceRequestId)
        }
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
          permission.loginAfter shouldBe defined
          permission.loginAfter.value.toInstant.isAfter(java.time.Instant.now()) shouldBe true
        }
      }

      withFrontEnd("sv1") { implicit webDriver =>
        clue("Create Vote proposal for bob with permenent revoked") {

          go to s"http://localhost:$sv1UIPort/governance"

          eventuallyClickOn(id("initiate-proposal-button"))

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

          eventually() {
            find(id("unpermission-validator-form")) should not be empty
          }

          fillOutTextField("unpermission-validator-participant-id", bobParticipantId)

          eventually() {
            val radio = webDriver.findElement(By.cssSelector("input[value='threshold']"))
            webDriver
              .asInstanceOf[org.openqa.selenium.JavascriptExecutor]
              .executeScript("arguments[0].click();", radio)
          }

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

          eventually() {
            webDriver.findElement(By.id("submit-button")).click()
          }
          eventually() {
            webDriver.findElement(By.id("submit-button")).getText shouldBe "Submit Proposal"
          }
          eventually() {
            webDriver.findElement(By.id("submit-button")).click()
          }

          eventually() {
            find(id("initiate-proposal-button")) should not be empty
          }
        }
      }

      val bobVoteRequest = clue("Vote Request for bob is created") {
        eventually() {
          sv1Backend
            .listVoteRequests()
            .find(req =>
              req.payload.action.toValue.toString.contains("SRARC_UnpermissionValidator") &&
                req.payload.action.toValue.toString.contains(bobParticipantId)
            )
            .value
        }
      }
      val bobRequestId = getTrackingId(bobVoteRequest).contractId

      clue("SV2 votes for bob revocation using UI") {
        withFrontEnd("sv2") { implicit webDriver =>
          svCastVoteOnActionRequired(sv2UIPort, sv2Backend, bobRequestId)
        }
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

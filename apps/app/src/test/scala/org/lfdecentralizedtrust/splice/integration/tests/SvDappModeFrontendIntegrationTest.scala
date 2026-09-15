// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CastVote,
  DsoRules_RequestVote,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.votedelegation.VoteDelegation
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.http.v0.definitions.{TreeEvent, UpdateHistoryItemV2}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.InitialPackageVersions
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.CloseVoteRequestTrigger
import org.lfdecentralizedtrust.splice.util.*
import org.openqa.selenium.{By, JavascriptExecutor, WebDriver}
import org.slf4j.event.Level

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** CIP-103 UI + reference wallet gateway coverage for SV dApp mode:
  * gateway allocates a voter party on alice's participant; SV creates
  * VoteDelegation; browser connects via the configured RemoteAdapter, discovers
  * the delegation from ACS, creates a request and casts a vote through
  * prepareExecuteAndWait, and approves the gateway popups. On-ledger
  * attribution remains the delegating SV.
  */
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_30
class SvDappModeFrontendIntegrationTest
    extends FrontendIntegrationTest("sv-dapp")
    with WalletTestUtil
    with SvTestUtil
    with WalletGatewayTestFixture {

  override protected def runTokenStandardCliSanityCheck: Boolean = false

  private val dsoGovernanceDarPath =
    s"daml/dars/splice-dso-governance-${InitialPackageVersions.initialPackageVersion(DarResources.dsoGovernance)}.dar"

  private val walletGatewayWindowTimeout = 30.seconds
  private val walletGatewayPopupTimeout = 45.seconds

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => ConfigTransforms.withNoVoteCooldown(config))
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Sv)(
          _.withPausedTrigger[CloseVoteRequestTrigger]
        )(config)
      )

  private def testId(id: String) = cssSelector(s"[data-testid='$id']")

  private def createVoteDelegation(
      svParty: PartyId,
      dsoParty: PartyId,
      voterParty: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): VoteDelegation.ContractId = {
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
      actAs = Seq(svParty),
      commands = new VoteDelegation(
        dsoParty.toProtoPrimitive,
        svParty.toProtoPrimitive,
        voterParty.toProtoPrimitive,
      ).create().commands.asScala.toSeq,
    )
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(VoteDelegation.COMPANION)(
        svParty,
        c =>
          c.data.voterParty == voterParty.toProtoPrimitive &&
            c.data.sv == svParty.toProtoPrimitive,
      )
      .loneElement
      .id
  }

  // Lit open-shadow on wallet-gateway and the CIP-103 picker; light-DOM finders miss them.

  private def js(implicit webDriver: WebDriver): JavascriptExecutor =
    webDriver.asInstanceOf[JavascriptExecutor]

  /** Deep querySelector across open shadow roots (Lit wallet UI). */
  private val deepQueryJs: String =
    """
    function queryDeep(sel, root) {
      const direct = root.querySelector(sel);
      if (direct) return direct;
      const all = root.querySelectorAll('*');
      for (let i = 0; i < all.length; i++) {
        const el = all[i];
        if (el.shadowRoot) {
          const found = queryDeep(sel, el.shadowRoot);
          if (found) return found;
        }
      }
      return null;
    }
    """

  private def shadowExists(selector: String)(implicit webDriver: WebDriver): Boolean =
    Option(
      js.executeScript(
        deepQueryJs + "return !!queryDeep(arguments[0], document);",
        selector,
      )
    ).exists(_.asInstanceOf[Boolean])

  private def shadowClick(selector: String)(implicit webDriver: WebDriver): Boolean =
    Option(
      js.executeScript(
        deepQueryJs +
          """
          const el = queryDeep(arguments[0], document);
          if (!el) return false;
          el.click();
          return true;
          """,
        selector,
      )
    ).exists(_.asInstanceOf[Boolean])

  private def shadowSetValue(selector: String, value: String)(implicit
      webDriver: WebDriver
  ): Boolean =
    Option(
      js.executeScript(
        deepQueryJs +
          """
          const el = queryDeep(arguments[0], document);
          if (!el) return false;
          el.focus();
          el.value = arguments[1];
          el.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
          el.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
          return true;
          """,
        selector,
        value,
      )
    ).exists(_.asInstanceOf[Boolean])

  private def shadowSelectByVisibleText(selector: String, label: String)(implicit
      webDriver: WebDriver
  ): Boolean =
    Option(
      js.executeScript(
        deepQueryJs +
          """
          const sel = queryDeep(arguments[0], document);
          if (!sel || !sel.options) return false;
          const wanted = arguments[1];
          for (let i = 0; i < sel.options.length; i++) {
            const opt = sel.options[i];
            const text = (opt.textContent || opt.text || '').replace(/\s+/g, ' ').trim();
            if (text === wanted) {
              sel.value = opt.value;
              sel.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
              sel.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
              return true;
            }
          }
          return false;
          """,
        selector,
        label,
      )
    ).exists(_.asInstanceOf[Boolean])

  private def shadowClickButtonNamed(name: String)(implicit webDriver: WebDriver): Boolean =
    Option(
      js.executeScript(
        """
        function findButton(text, root) {
          const buttons = root.querySelectorAll('button');
          for (let i = 0; i < buttons.length; i++) {
            const t = (buttons[i].textContent || '').replace(/\s+/g, ' ').trim();
            if ((t === text || t.includes(text)) && !buttons[i].disabled) {
              return buttons[i];
            }
          }
          const all = root.querySelectorAll('*');
          for (let i = 0; i < all.length; i++) {
            if (all[i].shadowRoot) {
              const found = findButton(text, all[i].shadowRoot);
              if (found) return found;
            }
          }
          return null;
        }
        const el = findButton(arguments[0], document);
        if (!el) return false;
        el.click();
        return true;
        """,
        name,
      )
    ).exists(_.asInstanceOf[Boolean])

  private def switchToWindow(handle: String)(implicit webDriver: WebDriver): Unit =
    webDriver.switchTo().window(handle)

  private def switchToWindowWithShadow(selector: String, exclude: String)(implicit
      webDriver: WebDriver
  ): Option[String] =
    webDriver.getWindowHandles.asScala
      .filterNot(_ == exclude)
      .find { h =>
        webDriver.switchTo().window(h)
        shadowExists(selector)
      }

  private def waitForWindowClose(
      handle: String,
      timeout: FiniteDuration = walletGatewayWindowTimeout,
  )(implicit webDriver: WebDriver): Unit =
    eventually(timeUntilSuccess = timeout) {
      webDriver.getWindowHandles.asScala.contains(handle) shouldBe false
    }

  /** Drive CIP-103 connect against the preconfigured RemoteAdapter RPC URL. */
  private def connectWalletThroughGateway()(implicit webDriver: WebDriver): Unit = {
    val mainWindow = webDriver.getWindowHandle
    val handlesBefore = webDriver.getWindowHandles.asScala

    eventuallyClickOn(testId("connect-wallet-button"))

    eventually(timeUntilSuccess = walletGatewayWindowTimeout) {
      webDriver.getWindowHandles.size should be > handlesBefore.size
    }

    clue("select CIP-103 RPC in shadowed wallet picker") {
      eventually() {
        switchToWindowWithShadow(
          """[aria-label="Connect to CIP-103 RPC"], .wallet-card, .custom-url-input""",
          mainWindow,
        ).nonEmpty shouldBe true
        val clicked =
          shadowClick("""[aria-label="Connect to CIP-103 RPC"]""") ||
            shadowClick(".wallet-card") || {
              if (shadowExists(".custom-url-input")) {
                shadowSetValue(".custom-url-input", walletGatewayDappApi) &&
                (shadowClick(".btn-add") || shadowClickButtonNamed("Connect"))
              } else false
            }
        clicked shouldBe true
      }
    }

    clue("complete wallet gateway self-signed login") {
      val loginWindow = eventually(timeUntilSuccess = walletGatewayPopupTimeout) {
        val handle = switchToWindowWithShadow("#network-select", mainWindow)
        handle.nonEmpty shouldBe true
        handle.value
      }
      switchToWindow(loginWindow)
      // Form auto-selects the first usable network; force the IT network by label.
      eventually() {
        shadowSelectByVisibleText("#network-select", walletGatewayNetworkName) shouldBe true
      }
      eventually(timeUntilSuccess = walletGatewayPopupTimeout) {
        switchToWindow(loginWindow)
        if (webDriver.getCurrentUrl.contains("/login")) {
          shadowSelectByVisibleText("#network-select", walletGatewayNetworkName)
          if (shadowExists("#client-id")) {
            shadowSetValue("#client-id", walletGatewayAuthClientId)
          }
          shadowClickButtonNamed("Connect")
        }
        webDriver.getCurrentUrl should not include "/login"
      }
    }

    switchToWindow(mainWindow)
  }

  private def shadowHasButtonNamed(name: String)(implicit webDriver: WebDriver): Boolean =
    Option(
      js.executeScript(
        """
        function hasButton(text, root) {
          const buttons = root.querySelectorAll('button');
          for (let i = 0; i < buttons.length; i++) {
            const t = (buttons[i].textContent || '').replace(/\s+/g, ' ').trim();
            if (t === text || t.includes(text)) return true;
          }
          const all = root.querySelectorAll('*');
          for (let i = 0; i < all.length; i++) {
            if (all[i].shadowRoot && hasButton(text, all[i].shadowRoot)) return true;
          }
          return false;
        }
        return hasButton(arguments[0], document);
        """,
        name,
      )
    ).exists(_.asInstanceOf[Boolean])

  private def approveGatewayTransaction()(implicit webDriver: WebDriver): Unit = {
    val mainWindow = webDriver.getWindowHandle
    val approveWindow = eventually(timeUntilSuccess = walletGatewayPopupTimeout) {
      val handle = webDriver.getWindowHandles.asScala
        .filterNot(_ == mainWindow)
        .find { h =>
          webDriver.switchTo().window(h)
          shadowHasButtonNamed("Approve")
        }
      handle.nonEmpty shouldBe true
      handle.value
    }
    switchToWindow(approveWindow)
    eventually() {
      shadowClickButtonNamed("Approve") shouldBe true
    }
    waitForWindowClose(approveWindow)
    switchToWindow(mainWindow)
  }

  private def fillOutTextField(elementId: String, text: String, chunkSize: Int = 16)(implicit
      webDriver: WebDriver
  ): Unit =
    eventually() {
      inside(find(id(elementId))) { case Some(element) =>
        text.grouped(chunkSize).foreach { chunk =>
          element.underlying.sendKeys(chunk)
        }
      }
    }

  private def createGrantFeaturedAppRequest(provider: PartyId)(implicit
      webDriver: WebDriver
  ): Unit = {
    go to s"http://localhost:$svDappUIPort/governance"
    eventually(timeUntilSuccess = walletGatewayWindowTimeout) {
      find(id("initiate-proposal-button")) should not be empty
    }
    click on id("initiate-proposal-button")
    eventually() {
      webDriver.findElement(By.id("select-action")).click()
    }
    eventually() {
      webDriver
        .findElement(By.cssSelector("[data-testid='SRARC_GrantFeaturedAppRight']"))
        .click()
    }
    eventually() {
      click on id("next-button")
    }
    eventually() {
      find(id("grant-featured-app-summary")) should not be empty
    }
    eventually() {
      webDriver.findElement(By.id("effective-at-threshold-radio")).click()
    }
    fillOutTextField("grant-featured-app-idValue", provider.toProtoPrimitive)
    fillOutTextField("grant-featured-app-summary", "dapp-mode create request")
    fillOutTextField("grant-featured-app-url", "https://example.com/dapp-proposal")
    eventually() {
      webDriver.findElement(By.id("submit-button")).isEnabled shouldBe true
    }
    click on id("submit-button")
    eventually() {
      webDriver.findElement(By.id("submit-button")).getText shouldBe "Submit Proposal"
    }
    click on id("submit-button")
    approveGatewayTransaction()
  }

  "SV dApp-mode UI" should {
    "connect wallet, create a request, and cast a vote attributed to the SV" in { implicit env =>
      clue("upload dso-governance DAR on alice's participant") {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(dsoGovernanceDarPath)
      }

      val dsoInfo = sv1Backend.getDsoInfo()
      val sv1Party = dsoInfo.svParty
      val dsoParty = dsoInfo.dsoParty
      val provider = sv2Backend.getDsoInfo().svParty

      try {
        // Gateway self_signed tokens use sub=alice_wallet_user. Canton rejects
        // JWTs for unknown users (UserNotFound → 403), which surfaces as
        // addSession "Failed to add session" / "Client version missing".
        // The wallet-app-client config names this user but does not create it.
        clue("ensure gateway auth ledger user exists on alice") {
          val users = aliceValidatorBackend.participantClientWithAdminToken.ledger_api.users
          if (!users.list().users.exists(_.id == walletGatewayAuthClientId)) {
            users.create(
              id = walletGatewayAuthClientId,
              actAs = Set.empty,
              primaryParty = None,
              readAs = Set.empty,
              participantAdmin = true,
            )
          }
        }

        val voterParty = clue("start wallet gateway and allocate participant-signed voter") {
          // Pin global-domain: alice's connectedSynchronizers[0] is splitwell.
          startWalletGateway(decentralizedSynchronizerId.toProtoPrimitive)
          createParticipantWallet(partyHint = s"dapp-voter-${System.currentTimeMillis()}")
        }

        clue("SV creates VoteDelegation for the gateway-allocated voter") {
          createVoteDelegation(sv1Party, dsoParty, voterParty)
        }

        // Lit / dapp-sdk console warnings are expected once the SDK chunk loads.
        loggerFactory.assertLogsSeq(
          SuppressionRule.LevelAndAbove(Level.WARN) &&
            SuppressionRule.LoggerNameContains("web-frontend")
        )(
          {
            withFrontEnd("sv-dapp") { implicit webDriver =>
              actAndCheck(
                "open dApp-mode SV UI", {
                  go to s"http://localhost:$svDappUIPort"
                },
              )(
                "connect-wallet screen is shown (not OIDC login)",
                _ => {
                  find(testId("connect-wallet-button")) should not be empty
                  find(id("user-id-field")) shouldBe empty
                },
              )

              actAndCheck(
                "connect CIP-103 wallet through the gateway", {
                  connectWalletThroughGateway()
                },
              )(
                "governance nav is available after ACS discovery",
                _ => {
                  find(testId("navlink-governance")) should not be empty
                  find(testId("wallet-login-error")) shouldBe empty
                },
              )

              actAndCheck(
                "create a vote request through the dapp", {
                  createGrantFeaturedAppRequest(provider)
                },
              )(
                "request is on ledger as the delegating SV",
                _ => {
                  find(id("initiate-proposal-button")) should not be empty
                  val created = sv1Backend.listVoteRequests().loneElement
                  created.payload.reason.body shouldBe "dapp-mode create request"
                  created.payload.votes.asScala.values
                    .map(_.sv) should contain(sv1Party.toProtoPrimitive)
                  created.payload.votes.asScala.values
                    .map(_.sv) should not contain voterParty.toProtoPrimitive
                },
              )

              val proposalCid = sv1Backend.listVoteRequests().loneElement.contractId.contractId

              actAndCheck(
                "open proposal and update the create-time vote via wallet gateway", {
                  go to s"http://localhost:$svDappUIPort/governance/proposals/$proposalCid"
                  eventually(timeUntilSuccess = walletGatewayWindowTimeout) {
                    (find(testId("your-vote-accept")).nonEmpty ||
                      find(testId("your-vote-edit-button")).nonEmpty) shouldBe true
                  }
                  if (find(testId("your-vote-accept")).isEmpty) {
                    click on testId("your-vote-edit-button")
                    eventually() {
                      find(testId("your-vote-accept")) should not be empty
                    }
                  }
                  inside(find(testId("your-vote-reason-input"))) { case Some(element) =>
                    element.underlying.sendKeys("dapp-mode delegated cast")
                  }
                  inside(find(testId("your-vote-url-input"))) { case Some(element) =>
                    element.underlying.sendKeys("https://example.com/dapp-vote")
                  }
                  click on testId("your-vote-accept")
                  approveGatewayTransaction()
                },
              )(
                "vote submission succeeds in the UI",
                _ =>
                  inside(find(testId("vote-submission-success"))) { case Some(element) =>
                    element.text should include("Vote successfully")
                  },
              )
            }
          },
          _ => succeed,
        )

        clue("on-ledger ballot is attributed to the delegating SV") {
          eventually() {
            val updated = sv1Backend
              .listVoteRequests()
              .find(_.payload.reason.body == "dapp-mode create request")
              .value
            val votes = updated.payload.votes.asScala.values.toSeq
            votes.map(_.sv) should contain(sv1Party.toProtoPrimitive)
            votes.map(_.sv) should not contain voterParty.toProtoPrimitive
            votes
              .find(_.sv == sv1Party.toProtoPrimitive)
              .value
              .accept shouldBe true
          }
        }

        clue("scan history shows the delegate on DsoRules_RequestVote and DsoRules_CastVote") {
          eventually() {
            val history = sv1ScanBackend.getUpdateHistory(1000, None, CompactJson)
            forExactly(1, history) {
              case UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(tx) =>
                forExactly(1, tx.eventsById.values) {
                  case TreeEvent.members.ExercisedEvent(ev)
                      if ev.choice == "DsoRules_RequestVote" =>
                    DsoRules_RequestVote
                      .fromJson(ev.choiceArgument.noSpaces)
                      .voterParty
                      .toScala
                      .value shouldBe voterParty.toProtoPrimitive
                  case _ => fail()
                }
              case _ => fail()
            }
            forExactly(1, history) {
              case UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(tx) =>
                forExactly(1, tx.eventsById.values) {
                  case TreeEvent.members.ExercisedEvent(ev) if ev.choice == "DsoRules_CastVote" =>
                    DsoRules_CastVote
                      .fromJson(ev.choiceArgument.noSpaces)
                      .voterParty
                      .toScala
                      .value shouldBe voterParty.toProtoPrimitive
                  case _ => fail()
                }
              case _ => fail()
            }
          }
        }
      } finally {
        stopWalletGateway()
      }
    }
  }
}

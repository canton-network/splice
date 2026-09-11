package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.*
import scala.util.Try

trait WalletGatewayFrontendTestUtil extends WalletFrontendTestUtil { self: FrontendTestCommon =>

  import WalletGatewayFrontendTestUtil.*
  import ShadowDom.*

  protected def validatorName: String

  protected lazy val walletUiUrl =
    s"https://wallet.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"
  protected lazy val walletGatewayUrl =
    s"https://walletgateway.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"
  protected lazy val portfolioUrl =
    s"https://portfolio.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"
  protected lazy val walletGatewayDappUrl = s"${walletGatewayUrl}api/v0/dapp"
  protected lazy val walletGatewayNetworkName = s"Splice ${validatorName}"

  protected def isWalletGatewayDeployed: Boolean = {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    val request = HttpRequest
      .newBuilder(URI.create(walletGatewayUrl))
      .timeout(Duration.ofSeconds(10))
      .GET()
      .build()
    Try(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).toOption
      .contains(200)
  }

  protected def onboardUserToValidatorWallet(user: Auth0User)(implicit
      webDriver: WebDriverType
  ): Unit = {
    // The gateway only allocates parties for an existing ledger user, which the wallet onboarding creates
    clue(s"Onboarding ${user.email} to the validator wallet at $walletUiUrl") {
      completeAuth0LoginWithAuthorization(
        walletUiUrl,
        user.email,
        user.password,
        () =>
          (find(id("onboard-button")).isDefined || find(
            className("party-id")
          ).isDefined) shouldBe true withClue "wallet UI onboarding page",
      )
      onboardUserAfterLogin()
    }
  }

  private def loginToWalletGatewayInCurrentWindow(
      user: Auth0User
  )(implicit webDriver: WebDriverType): Unit = {
    clue(s"Logging in to wallet gateway as ${user.email}") {
      actAndCheck(timeUntilSuccess = 1.minute)(
        "Select the network and connect", {
          // The select stays disabled until the gateway has fetched its networks
          eventually(1.minute) {
            val select = findDeep(Selectors.Gateway.networkSelect).valueOrFail("network select")
            select.isEnabled shouldBe true withClue "network select enabled (networks loaded)"
            selectDeepByVisibleText(select, walletGatewayNetworkName)
          }
          clickDeep(Selectors.Gateway.connectButton)
        },
      )(
        "Auth0 login form or the parties page is visible",
        _ => {
          if (!onGatewayPartiesPage) assertAuth0LoginFormVisible()
        },
      )
      if (!onGatewayPartiesPage) {
        submitAuth0LoginForm(
          user.email,
          user.password,
          () => onGatewayPartiesPage shouldBe true withClue "gateway parties page",
        )
      }
      waitForGatewayPartiesPage()
    }
  }

  private def onGatewayPartiesPage(implicit webDriver: WebDriverType): Boolean =
    currentUrl.contains("/parties")

  private def waitForGatewayPartiesPage()(implicit webDriver: WebDriverType): Unit =
    eventually(1.minute) {
      onGatewayPartiesPage shouldBe true withClue s"URL is the parties page: $currentUrl"
      findDeep(Selectors.Gateway.newPartyButton)
        .valueOrFail("'New' party button")
        .isDisplayed shouldBe true withClue "'New' party button is displayed (page loaded)"
    }

  private def createPrimaryParticipantWallet(
      partyHint: String
  )(implicit webDriver: WebDriverType): String = {
    clue(s"Creating primary participant wallet $partyHint") {
      actAndCheck(
        "Open the new party form",
        clickDeep(Selectors.Gateway.newPartyButton),
      )(
        "Party hint field is visible",
        _ => findDeep(Selectors.Gateway.partyHintField) should not be empty,
      )
      setDeepValue(eventuallyFindDeep(Selectors.Gateway.partyHintField), partyHint)
      setDeepValue(
        eventuallyFindDeep(Selectors.Gateway.signingProviderSelect),
        Selectors.Gateway.participantSigningProvider,
      )
      if (!eventuallyFindDeep(Selectors.Gateway.primaryCheckbox).isSelected)
        clickDeep(Selectors.Gateway.primaryCheckbox)
      val (_, wallet) = actAndCheck(timeUntilSuccess = 1.minute)(
        "Submit the new party form",
        clickDeep(Selectors.Gateway.submitButton),
      )(
        "The wallet card for the new party is visible",
        _ => findDeep(Selectors.Gateway.walletCard(partyHint)).valueOrFail("wallet card"),
      )
      val partyId = wallet.getAttribute("party-id")
      partyId should startWith(s"$partyHint::")
      partyId
    }
  }

  protected def connectPortfolioToWalletGateway(
      user: Auth0User
  )(implicit webDriver: WebDriverType): String = {
    val mainWindow = webDriver.getWindowHandle
    clue("Connecting the portfolio to the wallet gateway") {
      // The gateway is not in the picker's built-in list, so its dApp URL is entered as a custom wallet
      val windowsBefore = windowHandles
      eventuallyClickOn(xpath(Selectors.Portfolio.connectWalletButton))
      val picker = waitForNewWindow(windowsBefore)
      inWindow(picker) {
        val customUrlInput =
          eventuallyFindDeep(Selectors.Picker.customUrlInput, timeUntilSuccess = 1.minute)
        setDeepValue(customUrlInput, walletGatewayDappUrl)
        clickDeep(Selectors.Picker.customUrlConnectButton)
      }
      val gatewayWindow = eventually(1.minute) {
        (windowHandles - mainWindow).toSeq
          .find(handle => inWindow(handle)(currentUrl.contains("walletgateway")))
          .valueOrFail("a window showing the wallet gateway")
      }
      inWindow(gatewayWindow) {
        eventually(1.minute) {
          (findDeep(Selectors.Gateway.networkSelect).isDefined || findDeep(
            Selectors.Gateway.connectedStatus
          ).isDefined) shouldBe true withClue "gateway login form or connected status"
        }
        if (findDeep(Selectors.Gateway.connectedStatus).isEmpty) {
          loginToWalletGatewayInCurrentWindow(user)
        }
        eventually(1.minute) {
          findDeep(
            Selectors.Gateway.connectedStatus
          ) should not be empty withClue "connected status"
        }
      }
      webDriver.switchTo().window(mainWindow)
      eventually(1.minute) {
        currentUrl should include("/dashboard")
        find(
          xpath(Selectors.Portfolio.dashboardHeading)
        ) should not be empty withClue "Dashboard heading"
      }
      gatewayWindow
    }
  }

  protected def createPrimaryParticipantWalletInGateway(
      gatewayWindow: String,
      partyHint: String,
  )(implicit webDriver: WebDriverType): String =
    inWindow(gatewayWindow) {
      if (!onGatewayPartiesPage) {
        clickDeep(Selectors.Gateway.menuButton)
        clickDeep(Selectors.Gateway.menuItem, Some(Selectors.Gateway.partiesMenuItemText))
      }
      waitForGatewayPartiesPage()
      createPrimaryParticipantWallet(partyHint)
    }

  protected def waitForPortfolioWallet(partyHint: String)(implicit webDriver: WebDriverType): Unit =
    clue(s"Waiting for the portfolio to list wallet $partyHint") {
      eventually(1.minute) {
        find(xpath(Selectors.Portfolio.walletNavLink(partyHint))) should not be empty
      }
    }

  protected def loginAndLogoutFromWalletGateway(user: Auth0User)(implicit
      webDriver: WebDriverType
  ): Unit =
    clue("Logging in and out of the wallet gateway directly") {
      // Gateway sessions are per dApp origin, so this is a new (redirect-only, thanks to Auth0 SSO) login
      go to walletGatewayUrl
      loginToWalletGatewayInCurrentWindow(user)
      clickDeep(Selectors.Gateway.menuButton)
      actAndCheck(timeUntilSuccess = 1.minute)(
        "Click logout",
        clickDeep(Selectors.Gateway.menuItem, Some(Selectors.Gateway.logoutMenuItemText)),
      )(
        "Login page is visible",
        _ => currentUrl should include("/login"),
      )
    }

  protected def tapInPortfolio(amount: BigDecimal, partyHint: String)(implicit
      webDriver: WebDriverType
  ): Unit = {
    val mainWindow = webDriver.getWindowHandle
    clue(s"Tapping $amount through the portfolio") {
      // Navigate within the app: a full page load loses the connection to the gateway
      eventuallyClickOn(xpath(Selectors.Portfolio.navLink("Settings")))
      eventually() {
        find(
          xpath(Selectors.Portfolio.settingsHeading)
        ) should not be empty withClue "Settings heading"
      }
      // The DevNet section only renders once the portfolio has asked the validator whether this is a devnet
      eventually(1.minute) {
        find(xpath(Selectors.Portfolio.tapButton)) should not be empty withClue "DevNet tap button"
      }
      eventuallyClickOn(xpath(Selectors.Portfolio.tapButton))
      eventually() {
        find(xpath(Selectors.Portfolio.dialogText(s"$partyHint::"))) should not be empty withClue
          s"tap dialog targets the primary wallet $partyHint"
      }
      val amountField = eventually() {
        find(cssSelector(Selectors.Portfolio.tapAmountField)).valueOrFail("tap amount field")
      }
      webDriver.executeScript("arguments[0].select();", amountField.underlying)
      amountField.underlying.sendKeys(amount.toString)
      val windowsBefore = windowHandles
      eventuallyClickOn(xpath(Selectors.Portfolio.tapConfirmButton))
      approveInWalletGateway(mainWindow, windowsBefore)
      eventually(1.minute) {
        find(cssSelector(Selectors.Portfolio.dialog)) shouldBe empty withClue "tap dialog is closed"
      }
    }
  }

  private def approveInWalletGateway(mainWindow: String, windowsBefore: Set[String])(implicit
      webDriver: WebDriverType
  ): Unit = {
    clue("Approving the transaction in the wallet gateway") {
      val approveWindow = eventually(1.minute) {
        // The gateway reuses its popup window when one is already open, so it may not be a new handle
        (windowHandles - mainWindow).toSeq
          .find(handle => inWindow(handle)(currentUrl.contains("/approve")))
          .valueOrFail(s"a wallet gateway approve window (before: $windowsBefore)")
      }
      // The approve page enables its button before it has resolved the signing party; an early click fails
      // with "No primary wallet found", so keep clicking until the popup closes itself
      eventually(2.minutes, maxPollInterval = 10.seconds) {
        if (windowHandles.contains(approveWindow)) {
          inWindow(approveWindow) {
            clickDeep(
              Selectors.Gateway.approveButton,
              Some(Selectors.Gateway.approveButtonText),
              10.seconds,
            )
          }
        }
        windowHandles should not contain approveWindow withClue "approve popup closed itself"
      }
      webDriver.switchTo().window(mainWindow)
    }
  }

  protected def assertPortfolioShowsPositiveBalance()(implicit webDriver: WebDriverType): Unit = {
    clue("Checking the portfolio dashboard balance") {
      eventuallyClickOn(xpath(Selectors.Portfolio.navLink("Dashboard")))
      eventually(2.minutes) {
        val balances = findAll(cssSelector(Selectors.Portfolio.totalBalance)).toSeq
          .flatMap(_.attribute("aria-label"))
          .flatMap(Selectors.Portfolio.totalBalanceAmount.findFirstMatchIn(_))
          .map(m => BigDecimal(m.group(1).replace(",", "")))
        balances should not be empty withClue "total balance labels"
        forAtLeast(1, balances)(_ should be > BigDecimal(0))
      }
    }
  }
}

object WalletGatewayFrontendTestUtil {

  // Selectors of the upstream UIs (versions pinned in `cluster/configs/shared/wallet_gateway.yaml`). They are
  // matched per shadow root, so they must not span a shadow boundary.
  object Selectors {
    object Gateway {
      // Login page (`wg-login-form`)
      val networkSelect = "select#network-select"
      val connectButton = "button[type='submit']"
      // Header menu (`app-header`)
      val menuButton = "button.page-trigger"
      val menuItem = "button.menu-item"
      val logoutMenuItemText = "Logout"
      val partiesMenuItemText = "Parties"
      val connectedStatus = "[data-testid='network-status-connected']"
      // Parties page (`user-ui-parties`) and new party form (`wg-wallet-create-form`)
      val newPartyButton = "button.btn-add"
      def walletCard(partyHint: String) = s"wg-wallet-card[party-id^='$partyHint::']"
      val partyHintField = "input#party-id-hint"
      val signingProviderSelect = "select#signing-provider-id"
      val participantSigningProvider = "participant"
      val primaryCheckbox = "input#primary"
      val submitButton = "button[type='submit']"
      // Approve page (`wg-transaction-detail`)
      val approveButton = "button"
      val approveButtonText = "Approve"
    }
    object Picker {
      // The wallet picker popup renders into a shadow root of its own
      val customUrlInput = "input.custom-url-input"
      val customUrlConnectButton = "button.btn-add"
    }
    object Portfolio {
      val connectWalletButton = "//button[normalize-space()='Connect Wallet']"
      val dashboardHeading =
        "//h1[normalize-space()='Dashboard'] | //h2[normalize-space()='Dashboard']"
      def navLink(name: String) =
        s"//nav[@aria-label='Portfolio navigation']//a[normalize-space()='$name']"
      val settingsHeading = "//h1[normalize-space()='Settings']"
      def walletNavLink(partyHint: String) =
        s"//nav[@aria-label='Portfolio navigation']//a[contains(@href, '$partyHint')]"
      def dialogText(text: String) =
        s"//div[@role='dialog']//*[contains(normalize-space(), '$text')]"
      val tapButton =
        "//section[@aria-labelledby='devnet-heading']//button[normalize-space()='Tap']"
      val dialog = "[role='dialog']"
      val tapAmountField = "[role='dialog'] input[aria-label='Amount']"
      val tapConfirmButton = "//div[@role='dialog']//button[normalize-space()='Tap']"
      val totalBalance = "[aria-label^='Total balance:']"
      val totalBalanceAmount = raw"Total balance: ([0-9][0-9,]*(?:\.[0-9]+)?)".r
    }
  }
}

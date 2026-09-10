package org.lfdecentralizedtrust.splice.integration.tests.runbook

import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTest
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.util.Auth0Util.WithAuth0Support

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.*
import scala.util.{Random, Try}
import scala.util.control.NonFatal

abstract class WalletGatewayPreflightIntegrationTestBase
    extends FrontendIntegrationTest("wallet-gateway-user")
    with PreflightIntegrationTestUtil
    with WalletGatewayFrontendTestUtil
    with WithAuth0Support {

  override lazy val resetRequiredTopologyState: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  protected val auth0: Auth0Util
  protected val isDevNet: Boolean = true

  private var auth0User: Option[Auth0User] = None

  protected def validatorName: String

  protected lazy val walletUiUrl =
    s"https://wallet.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"
  override protected lazy val walletGatewayUrl =
    s"https://walletgateway.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"
  override protected lazy val portfolioUrl =
    s"https://portfolio.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"
  override protected lazy val walletGatewayNetworkName = s"Splice ${validatorName}"

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

  override protected def loginToWalletGatewayInCurrentWindow()(implicit
      webDriver: WebDriverType
  ): Unit = {
    val user = auth0User.value
    clue(s"Logging in to wallet gateway as ${user.email}") {
      actAndCheck(timeUntilSuccess = 1.minute)(
        "Select the network and connect",
        submitWalletGatewayLoginForm(None),
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

  override def beforeEach() = {
    super.beforeEach()
    TraceContext.withNewTraceContext("beforeEach")(implicit traceContext => {
      try {
        auth0User = Some(auth0.createUser())
      } catch {
        case NonFatal(e) =>
          // Logging the error, as an exception in this method will abort the test suite with no log output.
          logger.error("Creating the Auth0 user in beforeEach failed", e)
          throw e
      }
    })
  }

  override def afterEach() = {
    try super.afterEach()
    finally {
      auth0User.foreach(_.close())
      auth0User = None
    }
  }

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.svPreflightTopology(
      this.getClass.getSimpleName()
    )

  "run through the wallet gateway and portfolio UIs against the cluster validator" in { _ =>
    // The gateway is only enabled on some clusters (`walletGateway.enabled` in the cluster config)
    if (!isWalletGatewayDeployed) {
      cancel(s"No wallet gateway is deployed at $walletGatewayUrl, skipping")
    }
    val user = auth0User.value
    val partyHint = s"wg-preflight-${Random.alphanumeric.take(8).mkString.toLowerCase}"

    withFrontEnd("wallet-gateway-user") { implicit webDriver =>
      clue("Onboard the user to the validator through the wallet UI") {
        onboardUserToValidatorWallet(user)
      }

      val gatewayWindow = clue("Connect the portfolio to the wallet gateway") {
        go to s"${portfolioUrl}connect"
        connectPortfolioToWalletGateway()
      }

      clue("Create a primary wallet in the wallet gateway popup") {
        val partyId = createPrimaryParticipantWalletInGateway(gatewayWindow, partyHint)
        logger.info(s"Created wallet $partyId on the gateway")
        waitForPortfolioWallet(partyHint)
      }

      if (isDevNet) {
        clue("Tap through the portfolio and check the balance") {
          tapInPortfolio(BigDecimal(100), partyHint)
          assertPortfolioShowsPositiveBalance()
        }
      }

      loginAndLogoutFromWalletGateway()
    }
  }
}

class WalletGatewayValidator1PreflightIntegrationTest
    extends WalletGatewayPreflightIntegrationTestBase {
  override protected val auth0 = auth0UtilFromEnvVars("dev")
  override protected def validatorName = "validator1"
}

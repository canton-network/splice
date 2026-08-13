package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.config.IdentityDump
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTest
import org.lfdecentralizedtrust.splice.util.DataExportTestUtil
import org.lfdecentralizedtrust.splice.util.FrontendLoginUtil

import java.time.Instant

abstract class SvNonDevNetPreflightIntegrationTestBase
    extends FrontendIntegrationTest("sv")
    with SvUiPreflightIntegrationTestUtil
    with DataExportTestUtil
    with FrontendLoginUtil {

  override lazy val resetRequiredTopologyState: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  protected def svNumber: Int
  protected val svName = s"sv$svNumber"
  protected val svUrlPrefix = if (svNumber == 1) "sv-2" else s"sv-$svNumber-eng"
  protected val svNamespace = s"sv-$svNumber"

  protected def svClient(implicit env: SpliceTestConsoleEnvironment) = sv_client(svName)
  protected def svValidatorClient(implicit env: SpliceTestConsoleEnvironment) = vc(
    s"${svName}Validator"
  )
  protected def svScanClient(implicit env: SpliceTestConsoleEnvironment) = scancl(s"${svName}Scan")

  "SV reports devnet=false" in { implicit env =>
    svClient.getDsoInfo().dsoRules.payload.isDevNet shouldBe false
  }

  val svUsername = s"admin@${svName}.com"
  val svPassword = sys.env(s"SV_WEB_UI_PASSWORD")

  "SV can login to the SV UI" in { _ =>
    val svUiUrl = s"https://sv.$svUrlPrefix.${sys.env("NETWORK_APPS_ADDRESS")}/"

    withFrontEnd("sv") { implicit webDriver =>
      completeAuth0LoginWithAuthorization(
        svUiUrl,
        svUsername,
        svPassword,
        () => find(id("logout-button")) should not be empty withClue "'Logout' button",
      )
    }
  }

  "SV can login to the Name Service UI" in { _ =>
    val ansUrl = s"https://cns.$svUrlPrefix.${sys.env("NETWORK_APPS_ADDRESS")}"

    withFrontEnd("sv") { implicit webDriver =>
      completeAuth0LoginWithAuthorization(
        ansUrl,
        svUsername,
        svPassword,
        // if id("ans-entries") is visible, that implies:
        // 1) the logout button is visible
        // 2) the DirectoryInstall has been created (and therefore the request won't be aborted and thus flake)
        () => find(id("ans-entries")) should not be empty withClue "ANS Entries",
      )
    }
  }

  "SV can login to the wallet UI" in { _ =>
    val walletUrl = s"https://wallet.$svUrlPrefix.${sys.env("NETWORK_APPS_ADDRESS")}/"

    withFrontEnd("sv") { implicit webDriver =>
      completeAuth0LoginWithAuthorization(
        walletUrl,
        svUsername,
        svPassword,
        () => find(id("logout-button")) should not be empty withClue "'Logout' button",
      )
    }
  }

  "The Scan UI is working" in { _ =>
    val scanUrl = s"https://scan.$svUrlPrefix.${sys.env("NETWORK_APPS_ADDRESS")}"

    withFrontEnd("sv") { implicit webDriver =>
      go to scanUrl
      import scala.concurrent.duration.*
      eventually(3.minutes) {
        findAll(
          className("open-mining-round-row")
        ).length should be >= 2 withClue "open round table rows"
      }
    }
  }

  "Check readiness of SV applications" in { implicit env =>
    eventually() {
      forAll(
        Seq(
          svClient,
          svValidatorClient,
          svScanClient,
        )
      )(_.httpReady shouldBe true)
    }
  }

  "Check that there is a recent participant identities backup on GCP" in { _ =>
    testRecentParticipantIdentitiesDump(svNamespace, IdentityDump)
  }
}

final class Sv1NonDevNetPreflightIntegrationTest extends SvNonDevNetPreflightIntegrationTestBase {

  override protected def svNumber = 1

  "Check that sv-1 responds with recent open rounds" in { implicit env =>
    eventually() {
      val openRounds = svScanClient.getOpenAndIssuingMiningRounds()._1
      openRounds should not be empty withClue "open mining rounds must never be empty"
      val latestRound = openRounds.maxBy(_.contract.payload.round.number)
      latestRound.contract.payload.targetClosesAt shouldBe >=(
        Instant.now()
      ) withClue "latest open round must have a target close time in the future"
    }
  }
}

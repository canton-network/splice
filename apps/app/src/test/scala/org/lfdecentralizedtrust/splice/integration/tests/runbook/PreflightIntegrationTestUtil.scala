package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.console.ScanAppClientReference
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.sv.admin.api.client.commands.HttpSvOperatorAppClient
import org.lfdecentralizedtrust.splice.util.{ResourceTemplateDecoder, TemplateJsonDecoder}

import scala.concurrent.duration.*

trait PreflightIntegrationTestUtil extends TestCommon {

  /** Typed DSO info fetched via a scan's public `/v0/dso` endpoint. Used for SVs whose SV app
    * APIs the preflight tests have no credentials for, since the SV app's `/v1/dso` endpoint
    * requires authorization as SV operator.
    */
  protected def getDsoInfoViaScan(scan: ScanAppClientReference): HttpSvOperatorAppClient.DsoInfo = {
    implicit val decoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(
        ResourceTemplateDecoder.loadPackageSignaturesFromResources(
          DarResources.amulet.all ++ DarResources.dsoGovernance.all
        ),
        loggerFactory,
      )
    HttpSvOperatorAppClient
      .decodeDsoInfo(scan.getDsoInfo())
      .fold(
        err => throw new IllegalStateException(s"Failed to decode DSO info from scan: $err"),
        identity,
      )
  }

  // Give more time to the checks in cluster preflights on devnet only, to account for slower domains
  private def preflightTimeUntilSuccess: FiniteDuration = {
    sys.env.get("PREFLIGHT_DEFAULT_TIMEOUT_SECONDS").getOrElse("20").toInt.seconds
  }

  override def eventually[T](
      timeUntilSuccess: FiniteDuration = this.preflightTimeUntilSuccess,
      maxPollInterval: FiniteDuration = 5.seconds,
      retryOnTestFailuresOnly: Boolean = true,
  )(testCode: => T): T =
    super.eventually(timeUntilSuccess, maxPollInterval, retryOnTestFailuresOnly)(testCode)

  override def eventuallySucceeds[T](
      timeUntilSuccess: FiniteDuration = this.preflightTimeUntilSuccess,
      maxPollInterval: FiniteDuration = 5.seconds,
      suppressErrors: Boolean = true,
  )(testCode: => T): T =
    super.eventuallySucceeds(timeUntilSuccess, maxPollInterval, suppressErrors)(testCode)

  override def actAndCheck[T, U](
      timeUntilSuccess: FiniteDuration = this.preflightTimeUntilSuccess,
      maxPollInterval: FiniteDuration = 5.seconds,
  )(
      action: String,
      actionExpr: => T,
  )(check: String, checkFun: T => U): (T, U) =
    super.actAndCheck(timeUntilSuccess, maxPollInterval)(action, actionExpr)(check, checkFun)

}

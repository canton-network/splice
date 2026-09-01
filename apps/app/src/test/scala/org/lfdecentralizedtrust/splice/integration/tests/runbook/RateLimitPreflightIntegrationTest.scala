package org.lfdecentralizedtrust.splice.integration.tests.runbook

import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.scalatest.Assertion
import org.slf4j.event.Level

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, blocking}
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

class RateLimitPreflightIntegrationTest extends IntegrationTest {

  // istio tokens per ip
  private val istioGlobalPerIpLimit = 1000
  // istio rate limit response body
  private val istioRateLimitedBody = "local_rate_limited"

  override lazy val resetRequiredTopologyState: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  "Scan ACS requests are rate limited" in { implicit env =>
    forAll(Table("scan", env.scans.remote*)) { scanCli =>
      val dsoParty = scanCli.getDsoPartyId()
      rateLimitIsEnforced(
        20, {
          scanCli.getAcsSnapshot(
            // Dummy party that doesn't exist to avoid creating load
            PartyId.tryCreate(
              "rate-limit-party",
              dsoParty.namespace,
            ),
            None,
          )
        },
      )
    }
  }

  "Other scan requests are not rate limited" in { implicit env =>
    forAll(Table("scan", env.scans.remote*)) { scanCli =>
      rateLimitIsNotEnforced(
        10, {
          scanCli.getDsoPartyId()
        },
      )
    }
  }

  // Note: this test must come last, as it exhausts the per-IP token bucket of the scan it targets.
  "Requests exceeding the Istio per-IP limit are rejected by Istio" in { implicit env =>
    val scanCli = env.scans.remote.head

    val istioRejections = mutable.ListBuffer.empty[String]

    // The app's own rate limiter rejects requests as well, we only care about the requests
    // that Istio rejected before they ever reached the app.
    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
      collectResponses(
        istioGlobalPerIpLimit + istioGlobalPerIpLimit / 2,
        scanCli.getDsoPartyId(),
        timeout = 5.minutes,
        parallelism = 250,
      ),
      entries => {
        istioRejections ++= entries.map(_.message).filter(_.contains(istioRateLimitedBody))
        succeed
      },
    )

    inside(istioRejections.headOption) { case Some(message) =>
      message should include("429 Too Many Requests")
    }

    // Wait for the token bucket to refill, so that we don't affect any test running afterwards.
    eventually(2.minutes) {
      loggerFactory.suppressErrors(scanCli.getDsoPartyId())
    }
  }

  def rateLimitIsNotEnforced(limit: Int, call: => Unit)(implicit
      env: SpliceTestConsoleEnvironment
  ): Assertion = {
    val results = collectResponses(limit + 10, call)
    allWereSuccessfull(results)
  }
  def rateLimitIsEnforced(limit: Int, call: => Unit)(implicit
      env: SpliceTestConsoleEnvironment
  ): Assertion = {
    val results = loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
      collectResponses(limit, call),
      forAll(_)(entry =>
        forAtLeast(
          1,
          Seq(
            // This hits the Canton limit on concurrent requests
            "Reached the limit of concurrent requests for com.digitalasset.canton.admin.participant.v30.ParticipantRepairService/ExportAcs",
            // This hits the app's own rate limiter
            "Too Many Requests",
          ),
        )(entry.message should include(_))
      ),
    )
    // Note: failures are expected due to the Canton rate limiter.
    forAtLeast(1, results) {
      _ shouldBe a[scala.util.Success[?]]
    }
    assertThrowsAndLogsCommandFailures(
      call,
      entry => entry.message should include("Too Many Requests"),
    )
  }

  private def allWereSuccessfull(results: Seq[Try[Unit]]) = {
    results.collect { case Failure(NonFatal(exception)) =>
      exception
    } should be(empty)
  }

  private def collectResponses(
      limit: Int,
      call: => Unit,
      timeout: FiniteDuration = 1.minute,
      parallelism: Int = 64,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Seq[Try[Unit]] = {
    import env.executionContext
    Await.result(
      MonadUtil
        .parTraverseWithLimit(PositiveInt.tryCreate(parallelism))(
          Seq.fill(limit)(())
        )(_ => {
          Future {
            blocking {
              Try {
                call
              }
            }
          }
        }),
      timeout,
    )
  }

}

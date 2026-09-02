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

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, blocking}
import scala.util.control.NonFatal
import scala.util.{Failure, Try}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.client.RequestBuilding.Get
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader

class RateLimitPreflightIntegrationTest extends IntegrationTest {

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

  "Scan per-IP rate limits cannot be bypassed by spoofing X-Forwarded-For" in { implicit env =>
    import env.{actorSystem, executionContext}
    registerHttpConnectionPoolsCleanup(env)
    val perIpLimit = 120
    val requests = 200
    val scanCli = env.scans.remote.headOption.value
    val url = s"${scanCli.httpClientConfig.url}/registry/metadata/v1/info"
    val statuses = MonadUtil
      .parTraverseWithLimit(PositiveInt.tryCreate(16))((1 to requests).toSeq) { i =>
        Http()
          .singleRequest(
            Get(url).withHeaders(RawHeader("X-Forwarded-For", s"10.0.0.$i"))
          )
          .map { resp =>
            resp.discardEntityBytes()
            resp.status
          }
      }
      .futureValue
    val byStatus = statuses.groupBy(identity).view.mapValues(_.size).toMap
    val rejected = statuses.count(_ == StatusCodes.TooManyRequests)
    val accepted = statuses.count(_ == StatusCodes.OK)
    withClue(s"endpoint must be reachable, got $byStatus: ") {
      accepted should be > 0
    }
    withClue(s"XFF spoofing must not grant a fresh per-IP bucket, got $byStatus: ") {
      rejected should be >= (requests - perIpLimit)
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
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Seq[Try[Unit]] = {
    import env.executionContext
    Await.result(
      MonadUtil
        .parTraverseWithLimit(PositiveInt.tryCreate(32))(
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

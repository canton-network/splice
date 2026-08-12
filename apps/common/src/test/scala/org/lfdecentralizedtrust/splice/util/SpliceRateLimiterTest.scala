package org.lfdecentralizedtrust.splice.util

import com.daml.metrics.api.MetricsContext
import com.daml.metrics.api.testing.{InMemoryMetricsFactory, MetricValues}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.util.SpliceRateLimiterTest.runRateLimited
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class SpliceRateLimiterTest
    extends BaseTest
    with AnyWordSpecLike
    with HasActorSystem
    with HasExecutionContext
    with MetricValues {

  "the rate limiter" should {

    "accept requests under limit" in {
      val elementsToRun = 100
      withRateLimiter() { case (rateLimitMetrics, rateLimiter) =>
        runThroughRateLimiter(rateLimiter, 9, elementsToRun).reduce(_ && _) shouldBe true

        rateLimitMetrics.meter.valueFilteredOnLabels(
          LabelFilter(
            "result",
            "accepted",
          ),
          LabelFilter(
            "limiter",
            "test",
          ),
        ) shouldBe elementsToRun
      }
    }

    "reject requests that are over the limit" in {
      withRateLimiter() { case (rateLimitMetrics, rateLimiter) =>
        val results = runThroughRateLimiter(rateLimiter, 100, 1000)

        val (accepted, rejected) = results.partition(identity)

        // estimate for running 10 seconds, with some overhead for slower execution
        accepted.length should (be > 85 and be < 150)

        rateLimitMetrics.meter.valueFilteredOnLabels(
          LabelFilter(
            "result",
            "accepted",
          ),
          LabelFilter(
            "limiter",
            "test",
          ),
        ) should be(accepted.length)

        rateLimitMetrics.meter.valueFilteredOnLabels(
          LabelFilter(
            "result",
            "rejected",
          ),
          LabelFilter(
            "limiter",
            "test",
          ),
        ) should be(rejected.length)
      }

    }

  }

  "the per attribute rate limiter" should {

    "gate the per attribute limit on the overall limiter being enabled" in {
      PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = 42))
        .rateLimitFor(SpliceRateLimitConfig(ratePerSecond = 100))
        .ratePerSecond should be(42d)

      PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = 42))
        .rateLimitFor(SpliceRateLimitConfig(enabled = false, ratePerSecond = 100))
        .enabled should be(false)

      PerAttributeRateLimitConfig(
        enabled = false,
        limit = SpliceRateLimitConfig(ratePerSecond = 42),
      )
        .rateLimitFor(SpliceRateLimitConfig(ratePerSecond = 100))
        .enabled should be(false)

      PerAttributeRateLimitConfig.Disabled
        .rateLimitFor(SpliceRateLimitConfig(ratePerSecond = 100))
        .enabled should be(false)
    }

    "limit each attribute value separately" in {
      withPerAttributeRateLimiter(
        SpliceRateLimitConfig(ratePerSecond = 10),
        PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = 1)),
      ) { case (_, perAttributeRateLimiter) =>
        // 1 per second per attribute value, so a burst is rejected after the first request
        val ip1 = Seq.fill(20)(perAttributeRateLimiter.markRun(Some("1.1.1.1")))
        ip1.count(identity) should be(1)
        ip1.count(!_) should be(19)

        // a different attribute value is not affected by the limiter of the first one
        perAttributeRateLimiter.markRun(Some("2.2.2.2")) should be(true)
        perAttributeRateLimiter.markRun(Some("2.2.2.2")) should be(false)
      }
    }

    "use a single default limiter if the attribute value is unknown" in {
      withPerAttributeRateLimiter(
        SpliceRateLimitConfig(ratePerSecond = 10),
        PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = 1)),
      ) { case (metrics, perAttributeRateLimiter) =>
        val results = Seq.fill(20)(perAttributeRateLimiter.markRun(None))
        results.count(identity) should be(1)

        metrics.meter.valueFilteredOnLabels(
          LabelFilter("limiter", "test"),
          LabelFilter("limiter_attribute", "test_attribute"),
          LabelFilter("limiter_type", SpliceRateLimiter.UnknownAttributeLimiterType),
          LabelFilter("result", "rejected"),
        ) should be(results.count(!_))

        // requests with a known attribute value are not affected by the default limiter
        perAttributeRateLimiter.markRun(Some("1.1.1.1")) should be(true)
      }
    }

    "distinguish the metrics of the per attribute limiters" in {
      withPerAttributeRateLimiter(
        SpliceRateLimitConfig(ratePerSecond = 10),
        PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = 1)),
      ) { case (metrics, perAttributeRateLimiter) =>
        val results = Seq.fill(20)(perAttributeRateLimiter.markRun(Some("1.1.1.1")))

        metrics.meter.valueFilteredOnLabels(
          LabelFilter("limiter", "test"),
          LabelFilter("limiter_attribute", "test_attribute"),
          LabelFilter("limiter_type", SpliceRateLimiter.PerAttributeLimiterType),
          LabelFilter("result", "accepted"),
        ) should be(results.count(identity))
        metrics.meter.valueFilteredOnLabels(
          LabelFilter("limiter", "test"),
          LabelFilter("limiter_attribute", "test_attribute"),
          LabelFilter("limiter_type", SpliceRateLimiter.PerAttributeLimiterType),
          LabelFilter("result", "rejected"),
        ) should be(results.count(!_))
        // no metrics are reported for the default limiter of unknown attribute values
        metrics.meter.valuesWithContext.keys
          .flatMap(_.labels.get("limiter_type"))
          .toSet should be(Set(SpliceRateLimiter.PerAttributeLimiterType))
      }
    }

    "not limit anything if disabled" in {
      withPerAttributeRateLimiter(
        SpliceRateLimitConfig(ratePerSecond = 10),
        PerAttributeRateLimitConfig.Disabled,
      ) { case (_, perAttributeRateLimiter) =>
        Seq.fill(100)(perAttributeRateLimiter.markRun(Some("1.1.1.1"))) should contain only true
        Seq.fill(100)(perAttributeRateLimiter.markRun(None)) should contain only true
      }
    }

    "respect the configured rate over time" in {
      withPerAttributeRateLimiter(
        SpliceRateLimitConfig(ratePerSecond = 100),
        PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = 10)),
      ) { case (_, perAttributeRateLimiter) =>
        // 10 per second per attribute value
        val results = runRateLimited(50, 100) {
          if (perAttributeRateLimiter.markRun(Some("1.1.1.1"))) Future.successful(true)
          else
            Future.failed(
              io.grpc.Status.RESOURCE_EXHAUSTED
                .withDescription("Rate limit exceeded")
                .asRuntimeException()
            )
        }.futureValue
        // roughly 2 seconds of runtime at 10 permits per second, with some slack
        results.count(identity) should (be >= 5 and be <= 40)
        results.count(!_) should be > 0
      }
    }

  }

  "the rate limiter with a sustained limit" should {

    "throttle to the sustained rate once the burst budget is drained" in {
      withRateLimiter(
        SpliceRateLimitConfig(ratePerSecond = 1000, sustainedRatePerSecond = Some(10))
      ) { case (_, rateLimiter) =>
        // The per-second limit is high enough to never reject the throttled input, so the sustained
        // limiter (10/s) is the binding constraint over the run. The sustained limiter starts with
        // an empty burst budget (Guava SmoothBursty semantics), so throughput tracks the sustained
        // rate plus a small initial allowance.
        val results = runRateLimited(40, 120) {
          rateLimiter.runWithLimit(Future.successful(true))
        }.futureValue
        // ~3 seconds of runtime at 10 permits/s, with generous slack
        results.count(identity) should (be >= 10 and be <= 60)
        results.count(!_) should be > 0
      }
    }
  }

  private def runThroughRateLimiter(
      rateLimiter: SpliceRateLimiter,
      runsPerSecond: Int,
      runFor: Int,
  ) = {
    runRateLimited(
      runsPerSecond,
      runFor,
    ) {
      rateLimiter
        .runWithLimit(Future.successful(true))
    } futureValue
  }

  private def withRateLimiter[A](
      config: SpliceRateLimitConfig = SpliceRateLimitConfig(enabled = true, ratePerSecond = 10)
  )(f: (SpliceRateLimitMetrics, SpliceRateLimiter) => A): A = {
    val metricsFactory = new InMemoryMetricsFactory()
    val rateLimitMetrics = SpliceRateLimitMetrics(metricsFactory, logger)(MetricsContext.Empty)
    val rateLimiter = new SpliceRateLimiter(
      "test",
      config,
      rateLimitMetrics,
    )
    try {
      f(rateLimitMetrics, rateLimiter)
    } finally {
      rateLimitMetrics.close()
    }
  }

  private def withPerAttributeRateLimiter[A](
      config: SpliceRateLimitConfig,
      attributeConfig: PerAttributeRateLimitConfig,
  )(f: (SpliceRateLimitMetrics, PerAttributeRateLimiter) => A): A = {
    val metricsFactory = new InMemoryMetricsFactory()
    val rateLimitMetrics = SpliceRateLimitMetrics(metricsFactory, logger)(MetricsContext.Empty)
    val rateLimiter = new PerAttributeRateLimiter(
      "test",
      "test_attribute",
      config,
      attributeConfig,
      rateLimitMetrics,
      // no cold start delay in tests
      Instant.now().minusSeconds(1),
      logger,
    )
    try {
      f(rateLimitMetrics, rateLimiter)
    } finally {
      rateLimitMetrics.close()
    }
  }
}

object SpliceRateLimiterTest {

  def runRateLimited(runRate: Int, elementsToRun: Int)(
      run: => Future[?]
  )(implicit
      mat: Materializer
  ): Future[Seq[Boolean]] = {
    import mat.executionContext
    Source
      .repeat(())
      .take(elementsToRun.longValue())
      .throttle(runRate, 1.second)
      .mapAsync(elementsToRun)(_ =>
        run
          .map(_ => true)
          .recover {
            case rejection: StatusRuntimeException
                if rejection.getStatus.getCode == Status.Code.RESOURCE_EXHAUSTED =>
              false
            case failure: HttpCommandException if failure.status == StatusCodes.TooManyRequests =>
              false
            // match the raw command failure because it hides the root cause
            // should be enough because we assert on the number of successes vs failures
            case _: CommandFailure =>
              false
          }
      )
      // throttle after as well to ensure that even for runs that take a while to execute we still keep the rate
      .throttle(runRate, 1.second)
      .runWith(Sink.seq)
  }

}

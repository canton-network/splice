// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

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

    "start with the configured rate per second worth of permits" in {
      withRateLimiter(SpliceRateLimitConfig(ratePerSecond = 10)) { case (_, rateLimiter) =>
        // the limiter must not have to warm up first: it holds its configured rate worth of permits
        // (10) right from its creation, plus guava's deferred payment for the next one
        val results = Seq.fill(50)(rateLimiter.markRun())
        results.take(11) should contain only true
        results.count(!_) should be > 35
      }
    }

    "not create any limiter if disabled" in {
      // a disabled limiter must not fail even for a rate that guava would reject
      withRateLimiter(SpliceRateLimitConfig(enabled = false, ratePerSecond = 0)) {
        case (_, rateLimiter) =>
          Seq.fill(100)(rateLimiter.markRun()) should contain only true
      }
    }

    "reject everything if the rate is zero" in {
      withRateLimiter(SpliceRateLimitConfig(ratePerSecond = 0)) {
        case (rateLimitMetrics, rateLimiter) =>
          Seq.fill(100)(rateLimiter.markRun()) should contain only false

          rateLimitMetrics.meter.valueFilteredOnLabels(
            LabelFilter("limiter", "test"),
            LabelFilter("result", "rejected"),
          ) should be(100)
      }
    }

    "reject everything if the sustained rate is zero" in {
      withRateLimiter(
        SpliceRateLimitConfig(ratePerSecond = 100, sustainedRatePerSecond = Some(0))
      ) { case (_, rateLimiter) =>
        Seq.fill(100)(rateLimiter.markRun()) should contain only false
      }
    }

  }

  "the per attribute rate limiter" should {

    "limit each attribute value separately" in {
      withPerAttributeRateLimiter(
        PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = 1))
      ) { case (_, perAttributeRateLimiter) =>
        val ip1 = Seq.fill(20)(perAttributeRateLimiter.markRun(Some("1.1.1.1")))
        ip1.count(identity) should be(2)
        ip1.count(!_) should be(18)

        // a different attribute value is not affected by the limiter of the first one
        perAttributeRateLimiter.markRun(Some("2.2.2.2")) should be(true)
        perAttributeRateLimiter.markRun(Some("2.2.2.2")) should be(true)
        perAttributeRateLimiter.markRun(Some("2.2.2.2")) should be(false)
      }
    }

    "not limit requests with an unknown attribute value" in {
      withPerAttributeRateLimiter(
        PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = 1))
      ) { case (metrics, perAttributeRateLimiter) =>
        // requests without an attribute value are not rate limited here; the overall/global
        // rate limiter is relied upon to bound them instead
        val results = Seq.fill(20)(perAttributeRateLimiter.markRun(None))
        results.count(identity) should be(20)

        metrics.unknownAttributeNotLimited.valueFilteredOnLabels(
          LabelFilter("limiter", "test"),
          LabelFilter("limiter_attribute", "test_attribute"),
          LabelFilter("limiter_type", SpliceRateLimiter.PerAttributeLimiterType),
        ) should be(20)

        metrics.meter.valuesWithContext.keys
          .flatMap(_.labels.get("limiter_attribute"))
          .toSeq should be(empty)

        // requests with a known attribute value are still limited
        perAttributeRateLimiter.markRun(Some("1.1.1.1")) should be(true)
      }
    }

    "distinguish the metrics of the per attribute limiters" in {
      withPerAttributeRateLimiter(
        PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = 1))
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
        // only per attribute limiters report metrics
        metrics.meter.valuesWithContext.keys
          .flatMap(_.labels.get("limiter_type"))
          .toSet should be(Set(SpliceRateLimiter.PerAttributeLimiterType))
      }
    }

    "not limit anything if disabled" in {
      withPerAttributeRateLimiter(PerAttributeRateLimitConfig.disabled) {
        case (_, perAttributeRateLimiter) =>
          Seq.fill(100)(perAttributeRateLimiter.markRun(Some("1.1.1.1"))) should contain only true
          Seq.fill(100)(perAttributeRateLimiter.markRun(None)) should contain only true
      }
    }

    "respect the configured rate over time" in {
      withPerAttributeRateLimiter(
        PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = 10))
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

    "reject all requests with an attribute value if the per attribute rate is zero" in {
      withPerAttributeRateLimiter(
        PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = 0))
      ) { case (_, perAttributeRateLimiter) =>
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("1.1.1.1"))) should contain only false
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("2.2.2.2"))) should contain only false
        // requests without an attribute value are still not limited here
        Seq.fill(20)(perAttributeRateLimiter.markRun(None)) should contain only true
      }
    }

  }

  "the per attribute rate limiter with attribute overrides" should {

    "use the custom limit of a matching attribute value" in {
      withPerAttributeRateLimiter(
        PerAttributeRateLimitConfig(
          limit = SpliceRateLimitConfig(ratePerSecond = 1),
          attributeOverrides = Map("1.1.1.1" -> SpliceRateLimitConfig(ratePerSecond = 3)),
        )
      ) { case (_, perAttributeRateLimiter) =>
        // the override grants 3 permits (plus guava's deferred payment) to the matching value
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("1.1.1.1"))).count(identity) should be(4)
        // other attribute values use the default limit
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("2.2.2.2"))).count(identity) should be(2)
      }
    }

    "exempt matching attribute values if the override is disabled" in {
      withPerAttributeRateLimiter(
        PerAttributeRateLimitConfig(
          limit = SpliceRateLimitConfig(ratePerSecond = 1),
          attributeOverrides =
            Map("1.1.1.1" -> SpliceRateLimitConfig(enabled = false, ratePerSecond = 0)),
        )
      ) { case (_, perAttributeRateLimiter) =>
        Seq.fill(100)(perAttributeRateLimiter.markRun(Some("1.1.1.1"))) should contain only true
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("2.2.2.2"))).count(identity) should be(2)
      }
    }

    "not apply overrides if per attribute limiting is disabled" in {
      withPerAttributeRateLimiter(
        PerAttributeRateLimitConfig(
          enabled = false,
          attributeOverrides = Map("1.1.1.1" -> SpliceRateLimitConfig(ratePerSecond = 1)),
        )
      ) { case (_, perAttributeRateLimiter) =>
        Seq.fill(100)(perAttributeRateLimiter.markRun(Some("1.1.1.1"))) should contain only true
      }
    }

    "use a custom matcher" in {
      val prefixMatcher
          : PerAttributeRateLimitConfig => String => Option[SpliceRateLimitConfig.Simple] =
        config =>
          attributeValue =>
            config.attributeOverrides.collectFirst {
              case (prefix, limit) if attributeValue.startsWith(prefix) => limit
            }

      withPerAttributeRateLimiter(
        PerAttributeRateLimitConfig(
          limit = SpliceRateLimitConfig(ratePerSecond = 1),
          attributeOverrides = Map("premium-" -> SpliceRateLimitConfig(ratePerSecond = 3)),
        ),
        prefixMatcher,
      ) { case (_, perAttributeRateLimiter) =>
        // every matching attribute value gets its own limiter with the overridden limit
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("premium-1"))).count(identity) should be(
          4
        )
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("premium-2"))).count(identity) should be(
          4
        )

        // non-matching attribute values use the default limit
        Seq
          .fill(20)(perAttributeRateLimiter.markRun(Some("standard-1")))
          .count(identity) should be(2)
      }
    }

    "not apply any override with the noOverrides matcher" in {
      withPerAttributeRateLimiter(
        PerAttributeRateLimitConfig(
          limit = SpliceRateLimitConfig(ratePerSecond = 1),
          attributeOverrides = Map("1.1.1.1" -> SpliceRateLimitConfig(ratePerSecond = 100)),
        ),
        PerAttributeRateLimiter.noOverrides,
      ) { case (_, perAttributeRateLimiter) =>
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("1.1.1.1"))).count(identity) should be(2)
      }
    }
  }

  "the per attribute rate limiter with IP CIDR overrides" should {

    "use the custom limit for IPs of a matching network" in {
      withClientIpRateLimiter(
        PerAttributeRateLimitConfig(
          limit = SpliceRateLimitConfig(ratePerSecond = 1),
          attributeOverrides = Map("10.0.0.0/8" -> SpliceRateLimitConfig(ratePerSecond = 3)),
        )
      ) { case (_, perAttributeRateLimiter) =>
        // the override grants 3 permits (plus guava's deferred payment) to every single matching IP
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("10.1.2.3"))).count(identity) should be(4)
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("10.4.5.6"))).count(identity) should be(4)

        // non-matching IPs use the default limit
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("11.1.2.3"))).count(identity) should be(2)
      }
    }

    "exempt matching IPs if the override is disabled" in {
      withClientIpRateLimiter(
        PerAttributeRateLimitConfig(
          limit = SpliceRateLimitConfig(ratePerSecond = 1),
          attributeOverrides =
            Map("10.0.0.0/8" -> SpliceRateLimitConfig(enabled = false, ratePerSecond = 0)),
        )
      ) { case (_, perAttributeRateLimiter) =>
        Seq.fill(100)(perAttributeRateLimiter.markRun(Some("10.1.2.3"))) should contain only true
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("11.1.2.3"))).count(identity) should be(2)
      }
    }

    "block matching IPs entirely if the override rate is zero" in {
      withClientIpRateLimiter(
        PerAttributeRateLimitConfig(
          limit = SpliceRateLimitConfig(ratePerSecond = 1),
          attributeOverrides = Map("10.0.0.0/8" -> SpliceRateLimitConfig(ratePerSecond = 0)),
        )
      ) { case (_, perAttributeRateLimiter) =>
        Seq.fill(100)(perAttributeRateLimiter.markRun(Some("10.1.2.3"))) should contain only false
        // non-matching IPs still use the default limit
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("11.1.2.3"))).count(identity) should be(2)
      }
    }

    "only limit matching IPs if the default limit is disabled" in {
      withClientIpRateLimiter(
        PerAttributeRateLimitConfig(
          limit = SpliceRateLimitConfig(enabled = false, ratePerSecond = 0),
          attributeOverrides = Map("10.0.0.0/8" -> SpliceRateLimitConfig(ratePerSecond = 1)),
        )
      ) { case (_, perAttributeRateLimiter) =>
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("10.1.2.3"))).count(identity) should be(2)
        Seq.fill(100)(perAttributeRateLimiter.markRun(Some("11.1.2.3"))) should contain only true
      }
    }

    "use the most specific matching network" in {
      withClientIpRateLimiter(
        PerAttributeRateLimitConfig(
          limit = SpliceRateLimitConfig(ratePerSecond = 100),
          attributeOverrides = Map(
            "10.0.0.0/8" -> SpliceRateLimitConfig(ratePerSecond = 3),
            "10.1.2.3" -> SpliceRateLimitConfig(ratePerSecond = 1),
          ),
        )
      ) { case (_, perAttributeRateLimiter) =>
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("10.1.2.3"))).count(identity) should be(2)
        Seq.fill(20)(perAttributeRateLimiter.markRun(Some("10.1.2.4"))).count(identity) should be(4)
      }
    }

    "limit IPs without a matching network with the default limit" in {
      withClientIpRateLimiter(
        PerAttributeRateLimitConfig(
          limit = SpliceRateLimitConfig(ratePerSecond = 1),
          attributeOverrides = Map("2001:db8::/32" -> SpliceRateLimitConfig(ratePerSecond = 3)),
        )
      ) { case (_, perAttributeRateLimiter) =>
        Seq
          .fill(20)(perAttributeRateLimiter.markRun(Some("2001:db8:0:0:0:0:0:0/64")))
          .count(identity) should be(4)
        Seq
          .fill(20)(perAttributeRateLimiter.markRun(Some("2001:db9:0:0:0:0:0:0/64")))
          .count(identity) should be(2)
      }
    }
  }

  "the rate limiter with a sustained limit" should {

    "throttle to the sustained rate once the burst budget is drained" in {
      withRateLimiter(
        SpliceRateLimitConfig(ratePerSecond = 1000, sustainedRatePerSecond = Some(10))
      ) { case (_, rateLimiter) =>
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
      config: PerAttributeRateLimitConfig,
      attributeMatcher: PerAttributeRateLimitConfig => String => Option[
        SpliceRateLimitConfig.Simple
      ] = PerAttributeRateLimiter.exactMatch,
  )(f: (SpliceRateLimitMetrics, PerAttributeRateLimiter) => A): A = {
    val metricsFactory = new InMemoryMetricsFactory()
    val rateLimitMetrics = SpliceRateLimitMetrics(metricsFactory, logger)(MetricsContext.Empty)
    val rateLimiter = new PerAttributeRateLimiter(
      "test",
      "test_attribute",
      config,
      rateLimitMetrics,
      logger,
      attributeMatcher,
    )
    try {
      f(rateLimitMetrics, rateLimiter)
    } finally {
      rateLimitMetrics.close()
    }
  }

  /** A limiter keyed by the client IP, i.e. one whose overrides are keyed by an IP network. */
  private def withClientIpRateLimiter[A](
      config: PerAttributeRateLimitConfig
  )(f: (SpliceRateLimitMetrics, PerAttributeRateLimiter) => A): A =
    withPerAttributeRateLimiter(config, IpCidrRateLimits.matchClientIp)(f)
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

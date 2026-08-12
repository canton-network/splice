// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.daml.metrics.api.testing.InMemoryMetricsFactory
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.concurrent.Threading
import org.apache.pekko.http.scaladsl.model.headers.{RawHeader, `X-Forwarded-For`, `X-Real-Ip`}
import org.apache.pekko.http.scaladsl.model.{
  AttributeKeys,
  HttpRequest,
  RemoteAddress,
  StatusCode,
  StatusCodes,
}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.lfdecentralizedtrust.splice.config.RateLimitersConfig
import org.lfdecentralizedtrust.splice.util.{PerAttributeRateLimitConfig, SpliceRateLimitConfig}
import org.scalatest.wordspec.AnyWordSpec

import java.net.InetAddress

class HttpRateLimiterTest extends AnyWordSpec with BaseTest with ScalatestRouteTest {

  "clientIp" should {

    "prefer X-Forwarded-For" in {
      HttpRateLimiter.clientIp(
        HttpRequest()
          .withHeaders(
            `X-Forwarded-For`(RemoteAddress(InetAddress.getByName("1.1.1.1"))),
            `X-Real-Ip`(RemoteAddress(InetAddress.getByName("2.2.2.2"))),
          )
          .withAttributes(
            Map(
              AttributeKeys.remoteAddress -> RemoteAddress(InetAddress.getByName("3.3.3.3"))
            )
          )
      ) should be(Some("1.1.1.1"))
    }

    "fall back to X-Real-Ip" in {
      HttpRateLimiter.clientIp(
        HttpRequest().withHeaders(`X-Real-Ip`(RemoteAddress(InetAddress.getByName("2.2.2.2"))))
      ) should be(Some("2.2.2.2"))
    }

    "fall back to the remote address attribute" in {
      HttpRateLimiter.clientIp(
        HttpRequest().withAttributes(
          Map(AttributeKeys.remoteAddress -> RemoteAddress(InetAddress.getByName("3.3.3.3")))
        )
      ) should be(Some("3.3.3.3"))
    }

    "return None if no IP can be determined" in {
      HttpRateLimiter.clientIp(HttpRequest()) should be(None)
      HttpRateLimiter.clientIp(
        HttpRequest().withHeaders(RawHeader("X-Forwarded-For", "not-an-ip"))
      ) should be(None)
    }
  }

  "the http rate limiter" should {

    "reject requests of a client IP over the global per client IP limit" in {
      // the global per client IP limiter is enabled by default
      withRoutes(
        globalPerClientIp = perClientIp(1)
      )("testOperation") { routes =>
        val route = routes("testOperation")
        val results = (1 to 20).map(_ => call(route, ip = Some("1.1.1.1")))
        // 1 request per second per client IP => the burst gets rejected
        results.count(_ == StatusCodes.OK) should be(1)
        results.count(_ == StatusCodes.TooManyRequests) should be(19)
      }
    }

    "not reject requests of other client IPs" in {
      withRoutes(
        globalPerClientIp = perClientIp(1)
      )("testOperation") { routes =>
        val route = routes("testOperation")
        (1 to 20)
          .map(_ => call(route, ip = Some("1.1.1.1")))
          .count(_ == StatusCodes.TooManyRequests) should be > 0
        call(route, ip = Some("2.2.2.2")) should be(StatusCodes.OK)
      }
    }

    "fall back to the default limiter if no client IP is known" in {
      withRoutes(
        globalPerClientIp = perClientIp(1)
      )("testOperation") { routes =>
        val route = routes("testOperation")
        call(route, ip = None) should be(StatusCodes.OK)
        (1 to 20)
          .map(_ => call(route, ip = None))
          .count(_ == StatusCodes.TooManyRequests) should be > 0
        // a request with a client IP uses a different limiter
        call(route, ip = Some("1.1.1.1")) should be(StatusCodes.OK)
      }
    }

    "apply the global per client IP limiter across operations" in {
      // the same client IP is limited regardless of the operation
      withRoutes(
        globalPerClientIp = perClientIp(1)
      )("operationA", "operationB") { routes =>
        call(routes("operationA"), ip = Some("1.1.1.1")) should be(StatusCodes.OK)
        call(routes("operationB"), ip = Some("1.1.1.1")) should be(StatusCodes.TooManyRequests)
      }
    }

    "apply the global overall limiter across operations" in {
      withRoutes(
        global = SpliceRateLimitConfig(ratePerSecond = 1),
        globalPerClientIp = PerAttributeRateLimitConfig.Disabled,
      )("operationA", "operationB") { routes =>
        // exhaust the global budget via operationA
        (1 to 20).map(_ => call(routes("operationA"), ip = Some("1.1.1.1")))
        // the global limiter ignores the operation and the client IP, so operationB is rejected too
        call(routes("operationB"), ip = Some("2.2.2.2")) should be(StatusCodes.TooManyRequests)
      }
    }

    "not apply the per operation client IP limiter by default" in {
      // no per client IP limiting configured for operations => requests from a single IP are only
      // bounded by the (high) overall limiters
      withRoutes()("testOperation") { routes =>
        val route = routes("testOperation")
        (1 to 20).map(_ => call(route, ip = Some("1.1.1.1"))) should contain only StatusCodes.OK
      }
    }

    "apply the per operation client IP limiter when enabled for an operation" in {
      withRoutes(
        perClientIpOverrides = Map("limitedOperation" -> perClientIp(1))
      )("limitedOperation", "otherOperation") { routes =>
        val results =
          (1 to 20).map(_ => call(routes("limitedOperation"), ip = Some("1.1.1.1")))
        results.count(_ == StatusCodes.OK) should be(1)
        // a different operation is not affected by the per operation client IP limiter
        call(routes("otherOperation"), ip = Some("1.1.1.1")) should be(StatusCodes.OK)
      }
    }

    "apply the per operation overall limiter" in {
      withRoutes(
        rateLimiters = Map("limitedOperation" -> SpliceRateLimitConfig(ratePerSecond = 1))
      )("limitedOperation", "otherOperation") { routes =>
        val results = (1 to 20).map(_ => call(routes("limitedOperation"), ip = Some("1.1.1.1")))
        results.count(_ == StatusCodes.TooManyRequests) should be > 0
        // a different operation uses a separate overall limiter and is not affected
        call(routes("otherOperation"), ip = Some("1.1.1.1")) should be(StatusCodes.OK)
      }
    }
  }

  private def perClientIp(ratePerSecond: Double): PerAttributeRateLimitConfig =
    PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = ratePerSecond))

  private def call(route: Route, ip: Option[String]): StatusCode = {
    val request = ip match {
      case Some(value) =>
        Get("/") ~> addHeader(`X-Forwarded-For`(RemoteAddress(InetAddress.getByName(value))))
      case None => Get("/")
    }
    request ~> route ~> check(status)
  }

  private def withRoutes[A](
      // high enough by default so that only the explicitly configured limiter kicks in
      default: SpliceRateLimitConfig = SpliceRateLimitConfig(ratePerSecond = 1000),
      rateLimiters: Map[String, SpliceRateLimitConfig] = Map.empty,
      global: SpliceRateLimitConfig = SpliceRateLimitConfig(ratePerSecond = 1000),
      globalPerClientIp: PerAttributeRateLimitConfig = PerAttributeRateLimitConfig.Disabled,
      perClientIpOverrides: Map[String, PerAttributeRateLimitConfig] = Map.empty,
  )(operations: String*)(f: Map[String, Route] => A): A = {
    // Any operation with a per client IP override needs its own overall limiter entry so that the
    // embedded per client IP limiter is used instead of the `default` one.
    val perOperationConfigs: Map[String, SpliceRateLimitConfig.WithPerClientIp] =
      (rateLimiters.keySet ++ perClientIpOverrides.keySet).map { operation =>
        operation -> withPerClientIp(
          rateLimiters.getOrElse(operation, default),
          perClientIpOverrides.getOrElse(operation, PerAttributeRateLimitConfig.Disabled),
        )
      }.toMap
    val rateLimiter = new HttpRateLimiter(
      RateLimitersConfig(
        default = withPerClientIp(default, PerAttributeRateLimitConfig.Disabled),
        rateLimiters = perOperationConfigs,
        global = withPerClientIp(global, globalPerClientIp),
      ),
      new InMemoryMetricsFactory(),
      loggerFactory.getTracedLogger(classOf[HttpRateLimiterTest]),
    )
    try {
      val routes = operations.map { operation =>
        operation -> rateLimiter.withRateLimit("testService")(operation) {
          complete(StatusCodes.OK)
        }
      }.toMap
      // the rate limiter only starts enforcing 1 second after it got created
      Threading.sleep(1100)
      f(routes)
    } finally {
      rateLimiter.close()
    }
  }

  private def withPerClientIp(
      overall: SpliceRateLimitConfig,
      perClientIp: PerAttributeRateLimitConfig,
  ): SpliceRateLimitConfig.WithPerClientIp =
    SpliceRateLimitConfig.WithPerClientIp(
      enabled = overall.enabled,
      ratePerSecond = overall.ratePerSecond,
      sustainedRatePerSecond = overall.sustainedRatePerSecond,
      sustainedWindowSeconds = overall.sustainedWindowSeconds,
      perClientIp = perClientIp,
    )
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.daml.metrics.api.testing.InMemoryMetricsFactory
import com.digitalasset.canton.BaseTest
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
import org.lfdecentralizedtrust.splice.config.{PerClientIpRateLimitConfig, RateLimitersConfig}
import org.lfdecentralizedtrust.splice.util.{
  PerAttributeRateLimitConfig,
  SpliceRateLimitConfig,
  SpliceRateLimiter,
}
import org.scalatest.wordspec.AnyWordSpec

import java.net.InetAddress

class HttpRateLimiterTest extends AnyWordSpec with BaseTest with ScalatestRouteTest {

  "clientIp" should {

    "prefer X-Forwarded-For" in {
      clientIp(
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
      clientIp(
        HttpRequest().withHeaders(`X-Real-Ip`(RemoteAddress(InetAddress.getByName("2.2.2.2"))))
      ) should be(Some("2.2.2.2"))
    }

    "ignore a non-IP value and fall back to the next header" in {
      clientIp(
        HttpRequest()
          .withHeaders(
            RawHeader("X-Forwarded-For", "evil.example.com"),
            `X-Real-Ip`(RemoteAddress(InetAddress.getByName("2.2.2.2"))),
          )
      ) should be(Some("2.2.2.2"))
    }

    "use the first address of a comma separated header value" in {
      clientIp(
        HttpRequest().withHeaders(RawHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2, 3.3.3.3"))
      ) should be(Some("1.1.1.1"))
    }

    "use the configured headers in order" in {
      val request = HttpRequest().withHeaders(
        RawHeader("X-Envoy-External-Address", "4.4.4.4"),
        `X-Forwarded-For`(RemoteAddress(InetAddress.getByName("1.1.1.1"))),
        `X-Real-Ip`(RemoteAddress(InetAddress.getByName("2.2.2.2"))),
      )
      clientIp(
        request,
        clientIpHeaders = Seq("x-envoy-external-address", "x-forwarded-for"),
      ) should be(Some("4.4.4.4"))
      clientIp(
        request,
        clientIpHeaders = Seq("x-real-ip", "x-envoy-external-address"),
      ) should be(Some("2.2.2.2"))
    }

    "not use headers that are not configured" in {
      clientIp(
        HttpRequest().withHeaders(
          `X-Forwarded-For`(RemoteAddress(InetAddress.getByName("1.1.1.1"))),
          `X-Real-Ip`(RemoteAddress(InetAddress.getByName("2.2.2.2"))),
        ),
        clientIpHeaders = Seq("x-envoy-external-address"),
      ) should be(None)
    }

    "match the configured headers case-insensitively" in {
      clientIp(
        HttpRequest().withHeaders(RawHeader("X-Envoy-External-Address", "4.4.4.4")),
        clientIpHeaders = Seq("X-Envoy-External-Address"),
      ) should be(Some("4.4.4.4"))
    }

    "not extract any IP when no headers are configured" in {
      clientIp(
        HttpRequest().withHeaders(
          `X-Forwarded-For`(RemoteAddress(InetAddress.getByName("1.1.1.1")))
        ),
        clientIpHeaders = Seq.empty,
      ) should be(None)
    }

    "not use the remote address of the transport connection" in {
      // the remote address is not exposed by the server, so it must not be relied upon
      clientIp(
        HttpRequest().withAttributes(
          Map(AttributeKeys.remoteAddress -> RemoteAddress(InetAddress.getByName("3.3.3.3")))
        )
      ) should be(None)
    }

    "return None if no IP can be determined" in {
      clientIp(HttpRequest()) should be(None)
      clientIp(
        HttpRequest().withHeaders(RawHeader("X-Forwarded-For", "not-an-ip"))
      ) should be(None)
    }
  }

  "the client IP used for rate limiting" should {

    "use the full address for IPv4 clients" in {
      clientIpOf("1.2.3.4") should be(Some("1.2.3.4"))
    }

    "group IPv6 clients by their /64 prefix" in {
      // the lower 64 bits (the interface identifier) are freely chosen by the client
      clientIpOf("2001:db8:0:1:1:2:3:4") should be(Some("2001:db8:0:1:0:0:0:0/64"))
      clientIpOf("2001:db8:0:1:ffff:ffff:ffff:ffff") should be(
        clientIpOf("2001:db8:0:1:1:2:3:4")
      )
      clientIpOf("2001:db8:0:1::") should be(clientIpOf("2001:db8:0:1:1:2:3:4"))
    }

    "not group IPv6 clients of different /64 networks" in {
      clientIpOf("2001:db8:0:2:1:2:3:4") should not be clientIpOf("2001:db8:0:1:1:2:3:4")
      clientIpOf("2001:db9:0:1:1:2:3:4") should not be clientIpOf("2001:db8:0:1:1:2:3:4")
    }

    "reject IPv6 addresses carrying a zone id" in {
      // zone ids are only meaningful locally and are not valid in an IP literal of a header
      clientIpOf("fe80::1:2:3:4%7") should be(None)
    }

    "use the IPv4 address for IPv4-mapped IPv6 clients" in {
      // clients behind a dual stack proxy can be reported as ::ffff:a.b.c.d, those must not end up
      // in a single /64 bucket shared by all IPv4 clients
      clientIpOf("::ffff:1.2.3.4") should be(Some("1.2.3.4"))
      clientIpOf("::ffff:1.2.3.4") should be(clientIpOf("1.2.3.4"))
      clientIpOf("::ffff:4.3.2.1") should not be clientIpOf("::ffff:1.2.3.4")
    }

    "apply the same grouping to all client IP sources" in {
      val expected = Some("2001:db8:0:1:0:0:0:0/64")
      val address = RemoteAddress(InetAddress.getByName("2001:db8:0:1:1:2:3:4"))
      clientIp(
        HttpRequest().withHeaders(RawHeader("X-Envoy-External-Address", "2001:db8:0:1:1:2:3:4")),
        clientIpHeaders = Seq("x-envoy-external-address"),
      ) should be(expected)
      clientIp(
        HttpRequest().withHeaders(`X-Forwarded-For`(address))
      ) should be(expected)
      clientIp(
        HttpRequest().withHeaders(`X-Real-Ip`(address))
      ) should be(expected)
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
        // 1 request per second per client IP, with 1 permit available from the creation of the
        // limiter plus guava's deferred payment for the next one => the rest of the burst is rejected
        results.count(_ == StatusCodes.OK) should be(2)
        results.count(_ == StatusCodes.TooManyRequests) should be(18)
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

    "limit IPv6 clients of the same /64 network together" in {
      withRoutes(
        globalPerClientIp = perClientIp(1)
      )("testOperation") { routes =>
        val route = routes("testOperation")
        // drain the budget of the /64 network
        (1 to 20)
          .map(_ => call(route, ip = Some("2001:db8:0:1:1:2:3:4")))
          .count(_ == StatusCodes.OK) should be > 0
        // a different address of the same /64 shares the limiter, so it is rejected
        call(route, ip = Some("2001:db8:0:1:ffff:ffff:ffff:ffff")) should be(
          StatusCodes.TooManyRequests
        )
        // a different /64 is a different client
        call(route, ip = Some("2001:db8:0:2:1:2:3:4")) should be(StatusCodes.OK)
      }
    }

    "not apply the per client IP limiter if no client IP is known" in {
      withRoutes(
        globalPerClientIp = perClientIp(1)
      )("testOperation") { routes =>
        val route = routes("testOperation")
        (1 to 20).map(_ => call(route, ip = None)) should contain only StatusCodes.OK
        val results = (1 to 20).map(_ => call(route, ip = Some("1.1.1.1")))
        results.count(_ == StatusCodes.OK) should be(2)
        results.count(_ == StatusCodes.TooManyRequests) should be(18)
      }
    }

    "not apply the per client IP limiters if no client IP headers are configured" in {
      withRoutes(
        globalPerClientIp = perClientIp(1),
        perClientIpOverrides = Map("testOperation" -> perClientIp(1)),
        clientIpHeaders = Seq.empty,
      )("testOperation") { fixture =>
        val route = fixture("testOperation")
        (1 to 20).map(_ => call(route, ip = Some("1.1.1.1"))) should contain only StatusCodes.OK
        forEvery(Seq("testOperation", HttpRateLimiter.GlobalLimiter)) { limiter =>
          fixture.requestsRejectedBy(
            limiter,
            SpliceRateLimiter.PerAttributeLimiterType,
          ) should be(0L)
        }
      }
    }

    "apply the global per client IP limiter across operations" in {
      // the same client IP is limited regardless of the operation
      withRoutes(
        globalPerClientIp = perClientIp(1)
      )("operationA", "operationB") { routes =>
        (1 to 20)
          .map(_ => call(routes("operationA"), ip = Some("1.1.1.1")))
          .count(_ == StatusCodes.OK) should be > 0
        call(routes("operationB"), ip = Some("1.1.1.1")) should be(StatusCodes.TooManyRequests)
      }
    }

    "apply the global overall limiter across operations" in {
      withRoutes(
        global = SpliceRateLimitConfig(ratePerSecond = 1),
        globalPerClientIp = PerAttributeRateLimitConfig.disabled,
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
        results.count(_ == StatusCodes.OK) should be(2)
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

    "use separate per operation limiters for equally named operations of different services" in {
      val rateLimiter = new HttpRateLimiter(
        RateLimitersConfig(
          default = withPerClientIp(
            SpliceRateLimitConfig(ratePerSecond = 1),
            PerAttributeRateLimitConfig.disabled,
          ),
          rateLimiters = Map.empty,
          global = withPerClientIp(
            SpliceRateLimitConfig(ratePerSecond = 1000),
            PerAttributeRateLimitConfig.disabled,
          ),
        ),
        new InMemoryMetricsFactory(),
        loggerFactory.getTracedLogger(classOf[HttpRateLimiterTest]),
      )
      try {
        val routeV1 =
          rateLimiter.withRateLimit("serviceV1")("sharedOperation")(complete(StatusCodes.OK))
        val routeV2 =
          rateLimiter.withRateLimit("serviceV2")("sharedOperation")(complete(StatusCodes.OK))
        (1 to 20)
          .map(_ => call(routeV1, ip = Some("1.1.1.1")))
          .count(_ == StatusCodes.TooManyRequests) should be > 0
        call(routeV2, ip = Some("1.1.1.1")) should be(StatusCodes.OK)
      } finally {
        rateLimiter.close()
      }
    }
  }

  "the order in which the rate limiters are applied" should {

    // A limiter only records (and thereby only consumes budget for) the requests that actually
    // reach it, as the limiters are combined with a short-circuiting `&&`. The tests below send a
    // burst of requests that is rejected by one limiter and assert that the limiters which must be
    // applied later only saw the requests that were accepted by the rejecting one.
    val Burst = 20
    val Rejecting = SpliceRateLimitConfig(ratePerSecond = 1)
    // high enough to never reject, so that the recorded requests are exactly the ones that got here
    val Downstream = SpliceRateLimitConfig(ratePerSecond = 1000)
    val PerAttribute = SpliceRateLimiter.PerAttributeLimiterType
    val Overall = SpliceRateLimiter.GlobalLimiterType

    // Sends a burst of requests from a single client IP and returns how many were accepted.
    def burst(fixture: HttpRateLimiterTest.Fixture, operation: String): Long = {
      val results = (1 to Burst).map(_ => call(fixture(operation), ip = Some("1.1.1.1")))
      results.count(_ == StatusCodes.TooManyRequests) should be > 0
      results.count(_ == StatusCodes.OK).toLong
    }

    def onlySawAcceptedRequests(
        fixture: HttpRateLimiterTest.Fixture,
        accepted: Long,
    )(limiters: (String, String)*) =
      forEvery(limiters) { case (limiter, limiterType) =>
        withClue(s"requests seen by the $limiterType limiter '$limiter': ") {
          fixture.requestsSeenBy(limiter, limiterType) should be(accepted)
          fixture.requestsRejectedBy(limiter, limiterType) should be(0L)
        }
      }

    "apply the per operation client IP limiter before the overall limiters" in {
      withRoutes(
        rateLimiters = Map("limitedOperation" -> Downstream),
        global = Downstream,
        perClientIpOverrides = Map("limitedOperation" -> perClientIp(1)),
      )("limitedOperation") { fixture =>
        val accepted = burst(fixture, "limitedOperation")
        onlySawAcceptedRequests(fixture, accepted)(
          "limitedOperation" -> Overall,
          HttpRateLimiter.GlobalLimiter -> Overall,
        )
      }
    }

    "apply the global per client IP limiter before the overall limiters" in {
      withRoutes(
        rateLimiters = Map("limitedOperation" -> Downstream),
        global = Downstream,
        globalPerClientIp = perClientIp(1),
      )("limitedOperation") { fixture =>
        val accepted = burst(fixture, "limitedOperation")
        onlySawAcceptedRequests(fixture, accepted)(
          "limitedOperation" -> Overall,
          HttpRateLimiter.GlobalLimiter -> Overall,
        )
      }
    }

    "apply the per operation client IP limiter before the global per client IP limiter" in {
      withRoutes(
        globalPerClientIp = perClientIp(1000),
        perClientIpOverrides = Map("limitedOperation" -> perClientIp(1)),
      )("limitedOperation") { fixture =>
        val accepted = burst(fixture, "limitedOperation")
        onlySawAcceptedRequests(fixture, accepted)(
          HttpRateLimiter.GlobalLimiter -> PerAttribute
        )
      }
    }

    "apply the per operation overall limiter before the global overall limiter" in {
      withRoutes(
        rateLimiters = Map("limitedOperation" -> Rejecting),
        global = Downstream,
      )("limitedOperation") { fixture =>
        val accepted = burst(fixture, "limitedOperation")
        onlySawAcceptedRequests(fixture, accepted)(
          HttpRateLimiter.GlobalLimiter -> Overall
        )
      }
    }

    "still reject requests that pass the per client IP limiters but exceed an overall limit" in {
      withRoutes(
        global = SpliceRateLimitConfig(ratePerSecond = 2),
        globalPerClientIp = perClientIp(1000),
        perClientIpOverrides = Map("testOperation" -> perClientIp(1000)),
      )("testOperation") { fixture =>
        // every request is below both per client IP limits, but the overall global limit applies
        val results = (1 to Burst).map(i => call(fixture("testOperation"), ip = Some(s"1.1.1.$i")))
        results.count(_ == StatusCodes.OK) should be < Burst
        results.count(_ == StatusCodes.TooManyRequests) should be > 0
      }
    }
  }

  "the per client IP CIDR overrides" should {

    "apply the custom limit to clients of a matching network" in {
      withRoutes(
        globalPerClientIp = perClientIp(
          1,
          cidrOverrides = Map("10.0.0.0/8" -> SpliceRateLimitConfig(ratePerSecond = 5)),
        )
      )("testOperation") { routes =>
        val route = routes("testOperation")
        // 5 permits (plus guava's deferred payment) for a client of the overridden network
        (1 to 20)
          .map(_ => call(route, ip = Some("10.1.2.3")))
          .count(_ == StatusCodes.OK) should be(6)
        // every IP of the network gets its own limiter
        (1 to 20)
          .map(_ => call(route, ip = Some("10.4.5.6")))
          .count(_ == StatusCodes.OK) should be(6)
        // clients outside of the network use the default per client IP limit
        (1 to 20)
          .map(_ => call(route, ip = Some("11.1.2.3")))
          .count(_ == StatusCodes.OK) should be(2)
      }
    }

    "exempt clients of a network with a disabled override" in {
      withRoutes(
        globalPerClientIp = perClientIp(
          1,
          cidrOverrides =
            Map("10.0.0.0/8" -> SpliceRateLimitConfig(enabled = false, ratePerSecond = 0)),
        )
      )("testOperation") { routes =>
        val route = routes("testOperation")
        (1 to 20).map(_ => call(route, ip = Some("10.1.2.3"))) should contain only StatusCodes.OK
        (1 to 20)
          .map(_ => call(route, ip = Some("11.1.2.3")))
          .count(_ == StatusCodes.TooManyRequests) should be > 0
      }
    }

    "block clients of a network whose override rate is zero" in {
      withRoutes(
        globalPerClientIp = perClientIp(
          1000,
          cidrOverrides = Map("10.0.0.0/8" -> SpliceRateLimitConfig(ratePerSecond = 0)),
        )
      )("testOperation") { routes =>
        val route = routes("testOperation")
        (1 to 20).map(_ =>
          call(route, ip = Some("10.1.2.3"))
        ) should contain only StatusCodes.TooManyRequests
        call(route, ip = Some("11.1.2.3")) should be(StatusCodes.OK)
      }
    }

    "apply the overrides of the per operation limiter" in {
      withRoutes(
        perClientIpOverrides = Map(
          "limitedOperation" -> perClientIp(
            1,
            cidrOverrides = Map("10.0.0.0/8" -> SpliceRateLimitConfig(ratePerSecond = 0)),
          )
        )
      )("limitedOperation", "otherOperation") { routes =>
        call(routes("limitedOperation"), ip = Some("10.1.2.3")) should be(
          StatusCodes.TooManyRequests
        )
        // a different operation is not affected
        call(routes("otherOperation"), ip = Some("10.1.2.3")) should be(StatusCodes.OK)
      }
    }
  }

  private def perClientIp(
      ratePerSecond: Double,
      cidrOverrides: Map[String, SpliceRateLimitConfig.Simple] = Map.empty,
  ): PerAttributeRateLimitConfig =
    PerAttributeRateLimitConfig(
      limit = SpliceRateLimitConfig(ratePerSecond = ratePerSecond),
      attributeOverrides = cidrOverrides,
    )

  private def clientIp(
      request: HttpRequest,
      clientIpHeaders: Seq[String] = RateLimitersConfig.DefaultClientIpHeaders,
  ): Option[String] = {
    val route =
      HttpRateLimiter.extractClientIpKey(clientIpHeaders) { extracted =>
        complete(extracted.getOrElse[String](HttpRateLimiterTest.NoClientIp))
      }
    request ~> route ~> check {
      status should be(StatusCodes.OK)
      Some(responseAs[String]).filterNot(_ == HttpRateLimiterTest.NoClientIp)
    }
  }

  private def clientIpOf(ip: String): Option[String] =
    clientIp(HttpRequest().withHeaders(RawHeader("X-Forwarded-For", ip)))

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
      globalPerClientIp: PerAttributeRateLimitConfig = PerAttributeRateLimitConfig.disabled,
      perClientIpOverrides: Map[String, PerAttributeRateLimitConfig] = Map.empty,
      clientIpHeaders: Seq[String] = RateLimitersConfig.DefaultClientIpHeaders,
  )(operations: String*)(f: HttpRateLimiterTest.Fixture => A): A = {
    // Any operation with a per client IP override needs its own overall limiter entry so that the
    // embedded per client IP limiter is used instead of the `default` one.
    val perOperationConfigs: Map[String, PerClientIpRateLimitConfig] =
      (rateLimiters.keySet ++ perClientIpOverrides.keySet).map { operation =>
        operation -> withPerClientIp(
          rateLimiters.getOrElse(operation, default),
          perClientIpOverrides.getOrElse(operation, PerAttributeRateLimitConfig.disabled),
        )
      }.toMap
    val metricsFactory = new InMemoryMetricsFactory()
    val rateLimiter = new HttpRateLimiter(
      RateLimitersConfig(
        default = withPerClientIp(default, PerAttributeRateLimitConfig.disabled),
        rateLimiters = perOperationConfigs,
        global = withPerClientIp(global, globalPerClientIp),
        clientIpHeaders = clientIpHeaders,
      ),
      metricsFactory,
      loggerFactory.getTracedLogger(classOf[HttpRateLimiterTest]),
    )
    try {
      val routes = operations.map { operation =>
        operation -> rateLimiter.withRateLimit("testService")(operation) {
          complete(StatusCodes.OK)
        }
      }.toMap
      f(HttpRateLimiterTest.Fixture(routes, metricsFactory))
    } finally {
      rateLimiter.close()
    }
  }

  private def withPerClientIp(
      overall: SpliceRateLimitConfig,
      perClientIp: PerAttributeRateLimitConfig,
  ): PerClientIpRateLimitConfig =
    PerClientIpRateLimitConfig(
      enabled = overall.enabled,
      ratePerSecond = overall.ratePerSecond,
      sustainedRatePerSecond = overall.sustainedRatePerSecond,
      sustainedWindowSeconds = overall.sustainedWindowSeconds,
      perClientIp = perClientIp,
    )
}

object HttpRateLimiterTest {
  private val NoClientIp = "<none>"

  /** The routes of the rate limited operations together with the metrics recorded by their rate
    * limiters. Requests are only recorded by the limiters they actually reach, which is what allows
    * asserting on the order in which the limiters are applied.
    */
  private final case class Fixture(
      routes: Map[String, Route],
      metricsFactory: InMemoryMetricsFactory,
  ) {

    def apply(operation: String): Route = routes(operation)

    /** Number of requests recorded by the given limiter, i.e. that were actually evaluated by it. */
    def requestsSeenBy(limiter: String, limiterType: String): Long =
      marks(limiter, limiterType, result = None)

    /** Number of requests the given limiter rejected. */
    def requestsRejectedBy(limiter: String, limiterType: String): Long =
      marks(limiter, limiterType, result = Some("rejected"))

    private def marks(limiter: String, limiterType: String, result: Option[String]): Long =
      metricsFactory.metrics.meters.values
        .flatMap(_.values)
        .flatMap(_.markers.toSeq)
        .collect {
          case (context, value)
              if context.labels.get("limiter").contains(limiter) &&
                context.labels.get("limiter_type").contains(limiterType) &&
                result.forall(expected => context.labels.get("result").contains(expected)) =>
            value.get()
        }
        .sum
  }
}

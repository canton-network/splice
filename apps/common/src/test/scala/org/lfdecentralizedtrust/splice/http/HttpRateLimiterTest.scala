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

  import HttpRateLimiterTest.{IpVersion, IpVersions}

  "clientIp" should {

    IpVersions.foreach { v =>
      s"prefer X-Forwarded-For (${v.name})" in {
        clientIp(
          HttpRequest()
            .withHeaders(
              `X-Forwarded-For`(remoteAddress(v.address(0))),
              `X-Real-Ip`(remoteAddress(v.address(1))),
            )
            .withAttributes(
              Map(
                AttributeKeys.remoteAddress -> remoteAddress(v.address(2))
              )
            )
        ) should be(Some(v.key(0)))
      }

      s"fall back to X-Real-Ip (${v.name})" in {
        clientIp(
          HttpRequest().withHeaders(`X-Real-Ip`(remoteAddress(v.address(1))))
        ) should be(Some(v.key(1)))
      }

      s"ignore a non-IP value and fall back to the next header (${v.name})" in {
        clientIp(
          HttpRequest()
            .withHeaders(
              RawHeader("X-Forwarded-For", "evil.example.com"),
              `X-Real-Ip`(remoteAddress(v.address(1))),
            )
        ) should be(Some(v.key(1)))
      }

      s"use the first address of a comma separated header value (${v.name})" in {
        clientIp(
          HttpRequest().withHeaders(
            RawHeader(
              "X-Forwarded-For",
              s"${v.address(0)}, ${v.address(1)}, ${v.address(2)}",
            )
          )
        ) should be(Some(v.key(0)))
      }

      s"use the configured headers in order (${v.name})" in {
        val request = HttpRequest().withHeaders(
          RawHeader("X-Envoy-External-Address", v.address(3)),
          `X-Forwarded-For`(remoteAddress(v.address(0))),
          `X-Real-Ip`(remoteAddress(v.address(1))),
        )
        clientIp(
          request,
          clientIpHeaders = Seq("x-envoy-external-address", "x-forwarded-for"),
        ) should be(Some(v.key(3)))
        clientIp(
          request,
          clientIpHeaders = Seq("x-real-ip", "x-envoy-external-address"),
        ) should be(Some(v.key(1)))
      }

      s"not use headers that are not configured (${v.name})" in {
        clientIp(
          HttpRequest().withHeaders(
            `X-Forwarded-For`(remoteAddress(v.address(0))),
            `X-Real-Ip`(remoteAddress(v.address(1))),
          ),
          clientIpHeaders = Seq("x-envoy-external-address"),
        ) should be(None)
      }

      s"match the configured headers case-insensitively (${v.name})" in {
        clientIp(
          HttpRequest().withHeaders(RawHeader("X-Envoy-External-Address", v.address(3))),
          clientIpHeaders = Seq("X-Envoy-External-Address"),
        ) should be(Some(v.key(3)))
      }

      s"not extract any IP when no headers are configured (${v.name})" in {
        clientIp(
          HttpRequest().withHeaders(
            `X-Forwarded-For`(remoteAddress(v.address(0)))
          ),
          clientIpHeaders = Seq.empty,
        ) should be(None)
      }

      s"not use the remote address of the transport connection (${v.name})" in {
        // the remote address is not exposed by the server, so it must not be relied upon
        clientIp(
          HttpRequest().withAttributes(
            Map(AttributeKeys.remoteAddress -> remoteAddress(v.address(2)))
          )
        ) should be(None)
      }

      s"apply the same grouping to all client IP sources (${v.name})" in {
        val expected = Some(v.key(0))
        clientIp(
          HttpRequest().withHeaders(RawHeader("X-Envoy-External-Address", v.address(0))),
          clientIpHeaders = Seq("x-envoy-external-address"),
        ) should be(expected)
        clientIp(
          HttpRequest().withHeaders(`X-Forwarded-For`(remoteAddress(v.address(0))))
        ) should be(expected)
        clientIp(
          HttpRequest().withHeaders(`X-Real-Ip`(remoteAddress(v.address(0))))
        ) should be(expected)
      }
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

    "not group IPv4 clients of the same network" in {
      // a whole IPv4 network is not assigned to a single client, so they are limited individually
      clientIpOf("1.2.3.4") should not be clientIpOf("1.2.3.5")
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

    "never use the same key for an IPv4 and an IPv6 client" in {
      clientIpOf("1.2.3.4") should not be clientIpOf("2001:db8:0:1::1")
    }
  }

  "the http rate limiter" should {

    IpVersions.foreach { v =>
      s"reject requests of a client IP over the global per client IP limit (${v.name})" in {
        // the global per client IP limiter is enabled by default
        withRoutes(
          globalPerClientIp = perClientIp(1)
        )("testOperation") { routes =>
          val route = routes("testOperation")
          val results = (1 to 20).map(_ => call(route, ip = Some(v.address(0))))
          // 1 request per second per client IP, with 1 permit available from the creation of the
          // limiter plus guava's deferred payment for the next one => the rest of the burst is rejected
          results.count(_ == StatusCodes.OK) should be(2)
          results.count(_ == StatusCodes.TooManyRequests) should be(18)
        }
      }

      s"not reject requests of other client IPs (${v.name})" in {
        withRoutes(
          globalPerClientIp = perClientIp(1)
        )("testOperation") { routes =>
          val route = routes("testOperation")
          (1 to 20)
            .map(_ => call(route, ip = Some(v.address(0))))
            .count(_ == StatusCodes.TooManyRequests) should be > 0
          call(route, ip = Some(v.address(1))) should be(StatusCodes.OK)
        }
      }

      s"not apply the per client IP limiter if no client IP is known (${v.name})" in {
        withRoutes(
          globalPerClientIp = perClientIp(1)
        )("testOperation") { routes =>
          val route = routes("testOperation")
          (1 to 20).map(_ => call(route, ip = None)) should contain only StatusCodes.OK
          val results = (1 to 20).map(_ => call(route, ip = Some(v.address(0))))
          results.count(_ == StatusCodes.OK) should be(2)
          results.count(_ == StatusCodes.TooManyRequests) should be(18)
        }
      }

      s"not apply the per client IP limiters if no client IP headers are configured (${v.name})" in {
        withRoutes(
          globalPerClientIp = perClientIp(1),
          perClientIpOverrides = Map("testOperation" -> perClientIp(1)),
          clientIpHeaders = Seq.empty,
        )("testOperation") { fixture =>
          val route = fixture("testOperation")
          (1 to 20).map(_ =>
            call(route, ip = Some(v.address(0)))
          ) should contain only StatusCodes.OK
          forEvery(Seq("testOperation", HttpRateLimiter.GlobalLimiter)) { limiter =>
            fixture.requestsRejectedBy(
              limiter,
              SpliceRateLimiter.PerAttributeLimiterType,
            ) should be(0L)
          }
        }
      }

      s"apply the global per client IP limiter across operations (${v.name})" in {
        // the same client IP is limited regardless of the operation
        withRoutes(
          globalPerClientIp = perClientIp(1)
        )("operationA", "operationB") { routes =>
          (1 to 20)
            .map(_ => call(routes("operationA"), ip = Some(v.address(0))))
            .count(_ == StatusCodes.OK) should be > 0
          call(routes("operationB"), ip = Some(v.address(0))) should be(
            StatusCodes.TooManyRequests
          )
        }
      }

      s"apply the global overall limiter across operations (${v.name})" in {
        withRoutes(
          global = SpliceRateLimitConfig(ratePerSecond = 1),
          globalPerClientIp = PerAttributeRateLimitConfig.disabled,
        )("operationA", "operationB") { routes =>
          // exhaust the global budget via operationA
          (1 to 20).map(_ => call(routes("operationA"), ip = Some(v.address(0))))
          // the global limiter ignores the operation and the client IP, so operationB is rejected too
          call(routes("operationB"), ip = Some(v.address(1))) should be(
            StatusCodes.TooManyRequests
          )
        }
      }

      s"not apply the per operation client IP limiter by default (${v.name})" in {
        // no per client IP limiting configured for operations => requests from a single IP are only
        // bounded by the (high) overall limiters
        withRoutes()("testOperation") { routes =>
          val route = routes("testOperation")
          (1 to 20).map(_ =>
            call(route, ip = Some(v.address(0)))
          ) should contain only StatusCodes.OK
        }
      }

      s"apply the per operation client IP limiter when enabled for an operation (${v.name})" in {
        withRoutes(
          perClientIpOverrides = Map("limitedOperation" -> perClientIp(1))
        )("limitedOperation", "otherOperation") { routes =>
          val results =
            (1 to 20).map(_ => call(routes("limitedOperation"), ip = Some(v.address(0))))
          results.count(_ == StatusCodes.OK) should be(2)
          // a different operation is not affected by the per operation client IP limiter
          call(routes("otherOperation"), ip = Some(v.address(0))) should be(StatusCodes.OK)
        }
      }

      s"apply the per operation overall limiter (${v.name})" in {
        withRoutes(
          rateLimiters = Map("limitedOperation" -> SpliceRateLimitConfig(ratePerSecond = 1))
        )("limitedOperation", "otherOperation") { routes =>
          val results =
            (1 to 20).map(_ => call(routes("limitedOperation"), ip = Some(v.address(0))))
          results.count(_ == StatusCodes.TooManyRequests) should be > 0
          // a different operation uses a separate overall limiter and is not affected
          call(routes("otherOperation"), ip = Some(v.address(0))) should be(StatusCodes.OK)
        }
      }

      s"use separate per operation limiters for equally named operations of different services (${v.name})" in {
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
            .map(_ => call(routeV1, ip = Some(v.address(0))))
            .count(_ == StatusCodes.TooManyRequests) should be > 0
          call(routeV2, ip = Some(v.address(0))) should be(StatusCodes.OK)
        } finally {
          rateLimiter.close()
        }
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

    "limit an IPv4 client and its IPv4-mapped IPv6 form with the same limiter" in {
      withRoutes(
        globalPerClientIp = perClientIp(1)
      )("testOperation") { routes =>
        val route = routes("testOperation")
        (1 to 20)
          .map(_ => call(route, ip = Some("1.2.3.4")))
          .count(_ == StatusCodes.TooManyRequests) should be > 0
        call(route, ip = Some("::ffff:1.2.3.4")) should be(StatusCodes.TooManyRequests)
      }
    }

    "limit IPv4 and IPv6 clients independently" in {
      withRoutes(
        globalPerClientIp = perClientIp(1)
      )("testOperation") { routes =>
        val route = routes("testOperation")
        (1 to 20)
          .map(_ => call(route, ip = Some("1.2.3.4")))
          .count(_ == StatusCodes.TooManyRequests) should be > 0
        call(route, ip = Some("2001:db8:0:1::1")) should be(StatusCodes.OK)
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
    def burst(fixture: HttpRateLimiterTest.Fixture, operation: String, ip: String): Long = {
      val results = (1 to Burst).map(_ => call(fixture(operation), ip = Some(ip)))
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

    IpVersions.foreach { v =>
      s"apply the per operation client IP limiter before the overall limiters (${v.name})" in {
        withRoutes(
          rateLimiters = Map("limitedOperation" -> Downstream),
          global = Downstream,
          perClientIpOverrides = Map("limitedOperation" -> perClientIp(1)),
        )("limitedOperation") { fixture =>
          val accepted = burst(fixture, "limitedOperation", v.address(0))
          onlySawAcceptedRequests(fixture, accepted)(
            "limitedOperation" -> Overall,
            HttpRateLimiter.GlobalLimiter -> Overall,
          )
        }
      }

      s"apply the global per client IP limiter before the overall limiters (${v.name})" in {
        withRoutes(
          rateLimiters = Map("limitedOperation" -> Downstream),
          global = Downstream,
          globalPerClientIp = perClientIp(1),
        )("limitedOperation") { fixture =>
          val accepted = burst(fixture, "limitedOperation", v.address(0))
          onlySawAcceptedRequests(fixture, accepted)(
            "limitedOperation" -> Overall,
            HttpRateLimiter.GlobalLimiter -> Overall,
          )
        }
      }

      s"apply the per operation client IP limiter before the global per client IP limiter (${v.name})" in {
        withRoutes(
          globalPerClientIp = perClientIp(1000),
          perClientIpOverrides = Map("limitedOperation" -> perClientIp(1)),
        )("limitedOperation") { fixture =>
          val accepted = burst(fixture, "limitedOperation", v.address(0))
          onlySawAcceptedRequests(fixture, accepted)(
            HttpRateLimiter.GlobalLimiter -> PerAttribute
          )
        }
      }

      s"apply the per operation overall limiter before the global overall limiter (${v.name})" in {
        withRoutes(
          rateLimiters = Map("limitedOperation" -> Rejecting),
          global = Downstream,
        )("limitedOperation") { fixture =>
          val accepted = burst(fixture, "limitedOperation", v.address(0))
          onlySawAcceptedRequests(fixture, accepted)(
            HttpRateLimiter.GlobalLimiter -> Overall
          )
        }
      }

      s"still reject requests that pass the per client IP limiters but exceed an overall limit (${v.name})" in {
        withRoutes(
          global = SpliceRateLimitConfig(ratePerSecond = 2),
          globalPerClientIp = perClientIp(1000),
          perClientIpOverrides = Map("testOperation" -> perClientIp(1000)),
        )("testOperation") { fixture =>
          // every request is below both per client IP limits, but the overall global limit applies
          val results =
            v.distinctClients(Burst).map(ip => call(fixture("testOperation"), ip = Some(ip)))
          results.count(_ == StatusCodes.OK) should be < Burst
          results.count(_ == StatusCodes.TooManyRequests) should be > 0
        }
      }
    }
  }

  "the per client IP CIDR overrides" should {

    IpVersions.foreach { v =>
      s"apply the custom limit to clients of a matching network (${v.name})" in {
        withRoutes(
          globalPerClientIp = perClientIp(
            1,
            cidrOverrides = Map(v.network -> SpliceRateLimitConfig(ratePerSecond = 5)),
          )
        )("testOperation") { routes =>
          val route = routes("testOperation")
          // 5 permits (plus guava's deferred payment) for a client of the overridden network
          (1 to 20)
            .map(_ => call(route, ip = Some(v.insideA)))
            .count(_ == StatusCodes.OK) should be(6)
          // every client of the network gets its own limiter
          (1 to 20)
            .map(_ => call(route, ip = Some(v.insideB)))
            .count(_ == StatusCodes.OK) should be(6)
          // clients outside of the network use the default per client IP limit
          (1 to 20)
            .map(_ => call(route, ip = Some(v.outside)))
            .count(_ == StatusCodes.OK) should be(2)
        }
      }

      s"exempt clients of a network with a disabled override (${v.name})" in {
        withRoutes(
          globalPerClientIp = perClientIp(
            1,
            cidrOverrides =
              Map(v.network -> SpliceRateLimitConfig(enabled = false, ratePerSecond = 0)),
          )
        )("testOperation") { routes =>
          val route = routes("testOperation")
          (1 to 20).map(_ => call(route, ip = Some(v.insideA))) should contain only StatusCodes.OK
          (1 to 20)
            .map(_ => call(route, ip = Some(v.outside)))
            .count(_ == StatusCodes.TooManyRequests) should be > 0
        }
      }

      s"block clients of a network whose override rate is zero (${v.name})" in {
        withRoutes(
          globalPerClientIp = perClientIp(
            1000,
            cidrOverrides = Map(v.network -> SpliceRateLimitConfig(ratePerSecond = 0)),
          )
        )("testOperation") { routes =>
          val route = routes("testOperation")
          (1 to 20).map(_ =>
            call(route, ip = Some(v.insideA))
          ) should contain only StatusCodes.TooManyRequests
          call(route, ip = Some(v.outside)) should be(StatusCodes.OK)
        }
      }

      s"apply the overrides of the per operation limiter (${v.name})" in {
        withRoutes(
          perClientIpOverrides = Map(
            "limitedOperation" -> perClientIp(
              1,
              cidrOverrides = Map(v.network -> SpliceRateLimitConfig(ratePerSecond = 0)),
            )
          )
        )("limitedOperation", "otherOperation") { routes =>
          call(routes("limitedOperation"), ip = Some(v.insideA)) should be(
            StatusCodes.TooManyRequests
          )
          // a different operation is not affected
          call(routes("otherOperation"), ip = Some(v.insideA)) should be(StatusCodes.OK)
        }
      }

      s"use the most specific of several matching networks (${v.name})" in {
        withRoutes(
          globalPerClientIp = perClientIp(
            1000,
            cidrOverrides = Map(
              v.network -> SpliceRateLimitConfig(ratePerSecond = 1000),
              v.hostOf(v.insideA) -> SpliceRateLimitConfig(ratePerSecond = 0),
            ),
          )
        )("testOperation") { routes =>
          val route = routes("testOperation")
          call(route, ip = Some(v.insideA)) should be(StatusCodes.TooManyRequests)
          // covered by the wider network only
          call(route, ip = Some(v.insideB)) should be(StatusCodes.OK)
        }
      }

      s"not apply an override to clients of the other IP version (${v.name})" in {
        withRoutes(
          globalPerClientIp = perClientIp(
            1000,
            cidrOverrides = Map(v.network -> SpliceRateLimitConfig(ratePerSecond = 0)),
          )
        )("testOperation") { routes =>
          val route = routes("testOperation")
          call(route, ip = Some(v.insideA)) should be(StatusCodes.TooManyRequests)
          // a client of the other IP version is never contained in the configured network
          call(route, ip = Some(other(v).insideA)) should be(StatusCodes.OK)
        }
      }

      s"apply an override matching every address of the IP version (${v.name})" in {
        withRoutes(
          globalPerClientIp = perClientIp(
            1000,
            cidrOverrides = Map(v.anyNetwork -> SpliceRateLimitConfig(ratePerSecond = 0)),
          )
        )("testOperation") { routes =>
          val route = routes("testOperation")
          call(route, ip = Some(v.address(0))) should be(StatusCodes.TooManyRequests)
          call(route, ip = Some(v.outside)) should be(StatusCodes.TooManyRequests)
          // a zero length prefix of one IP version does not match the other one
          call(route, ip = Some(other(v).address(0))) should be(StatusCodes.OK)
        }
      }
    }

    "match an IPv4-mapped IPv6 client against the plain IPv4 network" in {
      withRoutes(
        globalPerClientIp = perClientIp(
          1000,
          cidrOverrides = Map("10.0.0.0/8" -> SpliceRateLimitConfig(ratePerSecond = 0)),
        )
      )("testOperation") { routes =>
        call(routes("testOperation"), ip = Some("::ffff:10.1.2.3")) should be(
          StatusCodes.TooManyRequests
        )
      }
    }

    "apply an IPv6 override to all clients of the covered /64 networks" in {
      withRoutes(
        globalPerClientIp = perClientIp(
          1000,
          cidrOverrides = Map("2001:db8:a::/48" -> SpliceRateLimitConfig(ratePerSecond = 0)),
        )
      )("testOperation") { routes =>
        val route = routes("testOperation")
        forEvery(Seq("2001:db8:a::1", "2001:db8:a:1::1", "2001:db8:a:ffff:1:2:3:4")) { ip =>
          call(route, ip = Some(ip)) should be(StatusCodes.TooManyRequests)
        }
        call(route, ip = Some("2001:db8:b:1::1")) should be(StatusCodes.OK)
      }
    }

    "not apply an IPv6 override that is more specific than the /64 rate limiting key" in {
      // clients are grouped into /64s, so an override narrower than that can never match
      withRoutes(
        globalPerClientIp = perClientIp(
          1000,
          cidrOverrides = Map("2001:db8:a:1:2::/80" -> SpliceRateLimitConfig(ratePerSecond = 0)),
        )
      )("testOperation") { routes =>
        call(routes("testOperation"), ip = Some("2001:db8:a:1:2::1")) should be(StatusCodes.OK)
      }
    }
  }

  private def other(version: IpVersion): IpVersion =
    if (version == HttpRateLimiterTest.Ipv4) HttpRateLimiterTest.Ipv6 else HttpRateLimiterTest.Ipv4

  private def perClientIp(
      ratePerSecond: Double,
      cidrOverrides: Map[String, SpliceRateLimitConfig.Simple] = Map.empty,
  ): PerAttributeRateLimitConfig =
    PerAttributeRateLimitConfig(
      limit = SpliceRateLimitConfig(ratePerSecond = ratePerSecond),
      attributeOverrides = cidrOverrides,
    )

  private def remoteAddress(ip: String): RemoteAddress =
    RemoteAddress(InetAddress.getByName(ip))

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
        Get("/") ~> addHeader(`X-Forwarded-For`(remoteAddress(value)))
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

  private[http] final case class IpVersion(
      name: String,
      addresses: Seq[String],
      keys: Seq[String],
      network: String,
      insideA: String,
      insideB: String,
      outside: String,
      anyNetwork: String,
      hostNetwork: String => String,
      clientTemplate: Int => String,
  ) {
    def address(index: Int): String = addresses(index)

    def key(index: Int): String = keys(index)

    def hostOf(address: String): String = hostNetwork(address)

    /** `n` distinct clients, each with its own rate limiting key. */
    def distinctClients(n: Int): Seq[String] = (1 to n).map(clientTemplate)
  }

  private[http] val Ipv4: IpVersion = IpVersion(
    name = "IPv4",
    addresses = Seq("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4"),
    keys = Seq("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4"),
    network = "10.0.0.0/8",
    insideA = "10.1.2.3",
    insideB = "10.4.5.6",
    outside = "11.1.2.3",
    anyNetwork = "0.0.0.0/0",
    // a single IPv4 host is rate limited by its full address
    hostNetwork = address => s"$address/32",
    clientTemplate = i => s"1.1.1.$i",
  )

  private[http] val Ipv6: IpVersion = IpVersion(
    name = "IPv6",
    addresses = Seq("2001:db8:1::1", "2001:db8:2::1", "2001:db8:3::1", "2001:db8:4::1"),
    keys = Seq(
      "2001:db8:1:0:0:0:0:0/64",
      "2001:db8:2:0:0:0:0:0/64",
      "2001:db8:3:0:0:0:0:0/64",
      "2001:db8:4:0:0:0:0:0/64",
    ),
    network = "2001:db8:a::/48",
    insideA = "2001:db8:a:1::1",
    insideB = "2001:db8:a:2::1",
    outside = "2001:db8:b:1::1",
    anyNetwork = "::/0",
    // IPv6 clients are grouped by /64, so that is the most specific network that can match one
    hostNetwork = address => s"$address/64",
    // distinct /64s, as clients of the same /64 share a limiter
    clientTemplate = i => s"2001:db8:c:$i::1",
  )

  private[http] val IpVersions: Seq[IpVersion] = Seq(Ipv4, Ipv6)

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

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

import java.net.{Inet6Address, InetAddress}

class HttpRateLimiterTest extends AnyWordSpec with BaseTest with ScalatestRouteTest {

  "clientIp" should {

    "prefer the trusted X-Envoy-External-Address over spoofable headers" in {
      clientIp(
        HttpRequest()
          .withHeaders(
            RawHeader("X-Envoy-External-Address", "4.4.4.4"),
            `X-Forwarded-For`(RemoteAddress(InetAddress.getByName("1.1.1.1"))),
            `X-Real-Ip`(RemoteAddress(InetAddress.getByName("2.2.2.2"))),
          )
          .withAttributes(
            Map(
              AttributeKeys.remoteAddress -> RemoteAddress(InetAddress.getByName("3.3.3.3"))
            )
          )
      ) should be(Some("4.4.4.4"))
    }

    "ignore a non-IP X-Envoy-External-Address and fall back to the trusted transport address" in {
      clientIp(
        HttpRequest()
          .withHeaders(RawHeader("X-Envoy-External-Address", "evil.example.com"))
          .withAttributes(
            Map(AttributeKeys.remoteAddress -> RemoteAddress(InetAddress.getByName("3.3.3.3")))
          )
      ) should be(Some("3.3.3.3"))
    }

    "use a configurable trusted proxy header" in {
      clientIp(
        HttpRequest()
          .withHeaders(
            RawHeader("X-Trusted-Client-Ip", "4.4.4.4"),
            `X-Forwarded-For`(RemoteAddress(InetAddress.getByName("1.1.1.1"))),
          ),
        trustedClientIpHeader = "x-trusted-client-ip",
      ) should be(Some("4.4.4.4"))
    }

    "match the trusted proxy header case-insensitively" in {
      clientIp(
        HttpRequest().withHeaders(RawHeader("X-Envoy-External-Address", "4.4.4.4")),
        trustedClientIpHeader = "X-Envoy-External-Address",
      ) should be(Some("4.4.4.4"))
    }

    "not trust any proxy header when the trusted header is disabled" in {
      clientIp(
        HttpRequest().withHeaders(
          RawHeader("X-Envoy-External-Address", "4.4.4.4"),
          `X-Forwarded-For`(RemoteAddress(InetAddress.getByName("1.1.1.1"))),
        ),
        trustedClientIpHeader = "",
      ) should be(Some("1.1.1.1"))
    }

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

    "fall back to the remote address attribute" in {
      clientIp(
        HttpRequest().withAttributes(
          Map(AttributeKeys.remoteAddress -> RemoteAddress(InetAddress.getByName("3.3.3.3")))
        )
      ) should be(Some("3.3.3.3"))
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

    "ignore the zone id of IPv6 addresses" in {
      val scoped = Inet6Address.getByAddress(
        null,
        InetAddress.getByName("fe80::1:2:3:4").getAddress,
        7,
      )
      // sanity check that the zone id is part of the address representation
      scoped.getHostAddress should be("fe80:0:0:0:1:2:3:4%7")
      clientIpOf(scoped) should be(Some("fe80:0:0:0:0:0:0:0/64"))
    }

    "use the IPv4 address for IPv4-mapped IPv6 clients" in {
      // dual stack sockets can report IPv4 clients as ::ffff:a.b.c.d, those must not end up in a
      // single /64 bucket shared by all IPv4 clients
      val ipv4Mapped = Inet6Address.getByAddress(
        null,
        Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte, 0xff.toByte, 1, 2, 3, 4),
        0,
      )
      ipv4Mapped shouldBe a[Inet6Address]
      clientIpOf(ipv4Mapped) should be(Some("1.2.3.4"))
      clientIpOf(ipv4Mapped) should be(clientIpOf("1.2.3.4"))
      clientIpOf(
        Inet6Address.getByAddress(
          null,
          Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte, 0xff.toByte, 4, 3, 2, 1),
          0,
        )
      ) should not be clientIpOf(ipv4Mapped)
    }

    "apply the same grouping to all client IP sources" in {
      val expected = Some("2001:db8:0:1:0:0:0:0/64")
      val address = RemoteAddress(InetAddress.getByName("2001:db8:0:1:1:2:3:4"))
      clientIp(
        HttpRequest().withHeaders(RawHeader("X-Envoy-External-Address", "2001:db8:0:1:1:2:3:4"))
      ) should be(expected)
      clientIp(
        HttpRequest().withHeaders(`X-Forwarded-For`(address))
      ) should be(expected)
      clientIp(
        HttpRequest().withHeaders(`X-Real-Ip`(address))
      ) should be(expected)
      clientIp(
        HttpRequest().withAttributes(Map(AttributeKeys.remoteAddress -> address))
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

    "limit IPv6 clients of the same /64 network together" in {
      withRoutes(
        globalPerClientIp = perClientIp(1)
      )("testOperation") { routes =>
        val route = routes("testOperation")
        call(route, ip = Some("2001:db8:0:1:1:2:3:4")) should be(StatusCodes.OK)
        // a different address of the same /64 shares the limiter, so it is rejected
        call(route, ip = Some("2001:db8:0:1:ffff:ffff:ffff:ffff")) should be(
          StatusCodes.TooManyRequests
        )
        // a different /64 is a different client
        call(route, ip = Some("2001:db8:0:2:1:2:3:4")) should be(StatusCodes.OK)
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

    "use separate per operation limiters for equally named operations of different services" in {
      val rateLimiter = new HttpRateLimiter(
        RateLimitersConfig(
          default = withPerClientIp(
            SpliceRateLimitConfig(ratePerSecond = 1),
            PerAttributeRateLimitConfig.Disabled,
          ),
          rateLimiters = Map.empty,
          global = withPerClientIp(
            SpliceRateLimitConfig(ratePerSecond = 1000),
            PerAttributeRateLimitConfig.Disabled,
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
        // the rate limiter only starts enforcing 1 second after it got created
        Threading.sleep(1100)
        (1 to 20)
          .map(_ => call(routeV1, ip = Some("1.1.1.1")))
          .count(_ == StatusCodes.TooManyRequests) should be > 0
        call(routeV2, ip = Some("1.1.1.1")) should be(StatusCodes.OK)
      } finally {
        rateLimiter.close()
      }
    }
  }

  private def perClientIp(ratePerSecond: Double): PerAttributeRateLimitConfig =
    PerAttributeRateLimitConfig(limit = SpliceRateLimitConfig(ratePerSecond = ratePerSecond))

  private def clientIp(
      request: HttpRequest,
      trustedClientIpHeader: String = RateLimitersConfig.DefaultTrustedClientIpHeader,
  ): Option[String] = {
    val route = HttpRateLimiter.extractClientIpKey(trustedClientIpHeader) { extracted =>
      complete(extracted.getOrElse[String](HttpRateLimiterTest.NoClientIp))
    }
    request ~> route ~> check {
      status should be(StatusCodes.OK)
      Some(responseAs[String]).filterNot(_ == HttpRateLimiterTest.NoClientIp)
    }
  }

  private def clientIpOf(ip: String): Option[String] =
    clientIpOf(InetAddress.getByName(ip))

  private def clientIpOf(ip: InetAddress): Option[String] =
    clientIp(
      HttpRequest().withAttributes(Map(AttributeKeys.remoteAddress -> RemoteAddress(ip)))
    )

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

object HttpRateLimiterTest {
  private val NoClientIp = "<none>"
}

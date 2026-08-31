// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpecLike

class IpCidrRateLimitsTest extends BaseTest with AnyWordSpecLike {

  import IpCidrRateLimitsTest.{IpVersion, IpVersions}

  private val networkLimit = SpliceRateLimitConfig(ratePerSecond = 5)
  private val hostLimit = SpliceRateLimitConfig(ratePerSecond = 50)

  "the IP CIDR rate limit overrides" should {

    IpVersions.foreach { v =>
      s"match addresses within the configured network (${v.name})" in {
        val overrides = perClientIp(v.network -> networkLimit)

        forEvery(v.inside)(ip => limitFor(overrides, ip) should be(Some(networkLimit)))
        forEvery(v.outside)(ip => limitFor(overrides, ip) should be(empty))
      }

      s"match a bare IP address as a single host (${v.name})" in {
        val overrides = perClientIp(v.host -> hostLimit)

        limitFor(overrides, v.host) should be(Some(hostLimit))
        limitFor(overrides, v.otherHost) should be(empty)
      }

      s"use the most specific match (${v.name})" in {
        val overrides = perClientIp(v.network -> networkLimit, v.host -> hostLimit)

        limitFor(overrides, v.host) should be(Some(hostLimit))
        limitFor(overrides, v.otherHost) should be(Some(networkLimit))
      }

      s"match everything for a zero length prefix (${v.name})" in {
        val overrides = perClientIp(v.anyNetwork -> networkLimit)

        forEvery(v.inside ++ v.outside)(ip => limitFor(overrides, ip) should be(Some(networkLimit)))
        // does not apply to the other IP version
        forEvery(other(v).inside)(ip => limitFor(overrides, ip) should be(empty))
      }

      s"not match a client of the other IP version (${v.name})" in {
        val overrides = perClientIp(v.network -> networkLimit)

        forEvery(other(v).inside ++ other(v).outside)(ip =>
          limitFor(overrides, ip) should be(empty)
        )
      }

      s"not match values that are not IP addresses (${v.name})" in {
        val overrides = perClientIp(v.network -> networkLimit)

        limitFor(overrides, "not-an-ip") should be(empty)
        limitFor(overrides, "") should be(empty)
        // must not do a DNS lookup
        limitFor(overrides, "localhost") should be(empty)
      }

      s"return a reusable matcher for a config (${v.name})" in {
        val matcher = IpCidrRateLimits.matchClientIp(perClientIp(v.network -> networkLimit))

        forEvery(v.inside)(ip => matcher(ip) should be(Some(networkLimit)))
        forEvery(v.outside)(ip => matcher(ip) should be(empty))
      }

      s"reject invalid configurations (${v.name})" in {
        forAll(v.invalidCidrs) { cidr =>
          val config = perClientIp(cidr -> networkLimit)
          a[IllegalArgumentException] should be thrownBy IpCidrRateLimits.tryValidate(config)
          a[IllegalArgumentException] should be thrownBy IpCidrRateLimits
            .matchClientIp(config)(v.host)
        }
      }

      s"normalize the configured network (${v.name})" in {
        forEvery(v.normalizations) { case (configured, normalized) =>
          IpCidr.tryParse(configured).toString should be(normalized)
        }
      }
    }

    "not match a client network that is wider than the configured one" in {
      val overrides = perClientIp("2001:db8:0:0:1::/80" -> networkLimit)

      // the /64 the client is grouped into is not fully covered by the configured /80
      limitFor(overrides, "2001:db8:0:0:0:0:0:0/64") should be(empty)
    }

    "match IPv4-mapped IPv6 addresses like the plain IPv4 address" in {
      val overrides = perClientIp("192.0.2.0/24" -> networkLimit)

      limitFor(overrides, "::ffff:192.0.2.1") should be(Some(networkLimit))
      limitFor(overrides, "::ffff:192.0.3.1") should be(empty)
    }

    "treat an IPv4-mapped IPv6 network as the plain IPv4 network" in {
      // ::ffff:a.b.c.d is decoded as the IPv4 address, so the prefix length is an IPv4 one
      val overrides = perClientIp("::ffff:192.0.2.0/24" -> networkLimit)

      limitFor(overrides, "192.0.2.1") should be(Some(networkLimit))
      limitFor(overrides, "::ffff:192.0.2.1") should be(Some(networkLimit))
      limitFor(overrides, "192.0.3.1") should be(empty)
      // an IPv6 prefix length is therefore out of range for a mapped address
      a[IllegalArgumentException] should be thrownBy IpCidr.tryParse("::ffff:192.0.2.0/120")
    }

    "match IPv6 prefixes across the 64 bit word boundary" in {
      // /64 is exactly the boundary between the two words the address is stored in
      val at64 = perClientIp("2001:db8:0:1::/64" -> networkLimit)
      limitFor(at64, "2001:db8:0:1:ffff:ffff:ffff:ffff") should be(Some(networkLimit))
      limitFor(at64, "2001:db8:0:2::1") should be(empty)

      // a prefix beyond 64 bits must also discriminate on the low word
      val beyond64 = perClientIp("2001:db8:0:1:2:3::/96" -> networkLimit)
      limitFor(beyond64, "2001:db8:0:1:2:3:4:5") should be(Some(networkLimit))
      limitFor(beyond64, "2001:db8:0:1:2:4:0:0") should be(empty)

      // a full length /128 only matches the single host
      val host = perClientIp("2001:db8:0:1:2:3:4:5" -> hostLimit)
      limitFor(host, "2001:db8:0:1:2:3:4:5") should be(Some(hostLimit))
      limitFor(host, "2001:db8:0:1:2:3:4:6") should be(empty)
    }

    "match IPv4 prefixes that are not on a byte boundary" in {
      val overrides = perClientIp("10.1.2.0/23" -> networkLimit)

      limitFor(overrides, "10.1.2.255") should be(Some(networkLimit))
      limitFor(overrides, "10.1.3.255") should be(Some(networkLimit))
      limitFor(overrides, "10.1.4.1") should be(empty)
    }

    "not match anything without overrides" in {
      forEvery(IpVersions.flatMap(v => v.inside ++ v.outside)) { ip =>
        limitFor(PerAttributeRateLimitConfig(), ip) should be(empty)
      }
    }
  }

  private def other(version: IpVersion): IpVersion =
    if (version == IpCidrRateLimitsTest.Ipv4) IpCidrRateLimitsTest.Ipv6
    else IpCidrRateLimitsTest.Ipv4

  private def perClientIp(
      overrides: (String, SpliceRateLimitConfig.Simple)*
  ): PerAttributeRateLimitConfig =
    PerAttributeRateLimitConfig(attributeOverrides = overrides.toMap)

  private def limitFor(
      config: PerAttributeRateLimitConfig,
      clientIp: String,
  ): Option[SpliceRateLimitConfig.Simple] =
    IpCidrRateLimits.matchClientIp(config)(clientIp)
}

object IpCidrRateLimitsTest {

  /** The sample networks and addresses of a single IP version, so that every CIDR override test can
    * be run for both IPv4 and IPv6.
    *
    * @param inside
    *   client IPs contained in `network`, including the `/64` keys the HTTP rate limiter groups
    *   IPv6 clients into.
    * @param host
    *   a client IP contained in `network` that is also used as a single host override.
    * @param otherHost
    *   another client IP of `network` that is not covered by the `host` override.
    */
  private[util] final case class IpVersion(
      name: String,
      network: String,
      inside: Seq[String],
      outside: Seq[String],
      host: String,
      otherHost: String,
      anyNetwork: String,
      invalidCidrs: Seq[String],
      normalizations: Seq[(String, String)],
  )

  private[util] val Ipv4: IpVersion = IpVersion(
    name = "IPv4",
    network = "10.0.0.0/8",
    inside = Seq("10.1.2.3", "10.255.255.255", "10.0.0.0"),
    outside = Seq("11.0.0.1", "9.255.255.255"),
    host = "10.1.2.3",
    otherHost = "10.1.2.4",
    anyNetwork = "0.0.0.0/0",
    invalidCidrs = Seq(
      "not-an-ip/8",
      "10.0.0.0/33",
      "10.0.0.0/-1",
      "10.0.0.0/eight",
      "10.0.0.0/8/16",
      "10.0.0.256/8",
      "",
    ),
    normalizations = Seq(
      "10.1.2.3/8" -> "10.0.0.0/8",
      "10.1.2.3" -> "10.1.2.3/32",
      "10.1.2.3/32" -> "10.1.2.3/32",
      "10.1.2.3/0" -> "0.0.0.0/0",
      "10.1.2.3/23" -> "10.1.2.0/23",
    ),
  )

  private[util] val Ipv6: IpVersion = IpVersion(
    name = "IPv6",
    network = "2001:db8::/32",
    // the HTTP rate limiter groups IPv6 clients into /64s, so both forms must match
    inside = Seq("2001:db8:0:0:0:0:0:0/64", "2001:db8:1:2:3:4:5:6", "2001:db8::"),
    outside = Seq("2001:db9:0:0:0:0:0:0/64", "2001:db9::1"),
    host = "2001:db8:1:2:3:4:5:6",
    otherHost = "2001:db8:1:2:3:4:5:7",
    anyNetwork = "::/0",
    invalidCidrs = Seq(
      "not-an-ip/32",
      "2001:db8::/129",
      "2001:db8::/-1",
      "2001:db8::/thirtytwo",
      "2001:db8::/32/64",
      "2001:db8:::1/32",
      "",
    ),
    normalizations = Seq(
      "2001:db8:1:2:3:4:5:6/32" -> "2001:db8:0:0:0:0:0:0/32",
      "2001:db8:1:2:3:4:5:6/96" -> "2001:db8:1:2:3:4:0:0/96",
      "2001:db8:1:2:3:4:5:6/0" -> "0:0:0:0:0:0:0:0/0",
      "2001:db8:1:2:3:4:5:6" -> "2001:db8:1:2:3:4:5:6/128",
      "2001:db8:1:2:3:4:5:6/64" -> "2001:db8:1:2:0:0:0:0/64",
    ),
  )

  private[util] val IpVersions: Seq[IpVersion] = Seq(Ipv4, Ipv6)
}

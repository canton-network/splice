// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpecLike

class IpCidrRateLimitsTest extends BaseTest with AnyWordSpecLike {

  private val networkLimit = SpliceRateLimitConfig(ratePerSecond = 5)
  private val hostLimit = SpliceRateLimitConfig(ratePerSecond = 50)

  "the IP CIDR rate limit overrides" should {

    "match IPv4 addresses within the configured network" in {
      val overrides = perClientIp("10.0.0.0/8" -> networkLimit)

      limitFor(overrides, "10.1.2.3") should be(Some(networkLimit))
      limitFor(overrides, "10.255.255.255") should be(Some(networkLimit))
      limitFor(overrides, "11.0.0.1") should be(empty)
    }

    "match a bare IP address as a single host" in {
      val overrides = perClientIp("10.1.2.3" -> hostLimit)

      limitFor(overrides, "10.1.2.3") should be(Some(hostLimit))
      limitFor(overrides, "10.1.2.4") should be(empty)
    }

    "use the most specific match" in {
      val overrides = perClientIp("10.0.0.0/8" -> networkLimit, "10.1.2.3" -> hostLimit)

      limitFor(overrides, "10.1.2.3") should be(Some(hostLimit))
      limitFor(overrides, "10.1.2.4") should be(Some(networkLimit))
    }

    "match IPv6 addresses and the /64 keys used for rate limiting" in {
      val overrides = perClientIp("2001:db8::/32" -> networkLimit)

      limitFor(overrides, "2001:db8:0:0:0:0:0:0/64") should be(Some(networkLimit))
      limitFor(overrides, "2001:db8:1:2:3:4:5:6") should be(Some(networkLimit))
      limitFor(overrides, "2001:db9:0:0:0:0:0:0/64") should be(empty)
    }

    "not match a client network that is wider than the configured one" in {
      val overrides = perClientIp("2001:db8:0:0:1::/80" -> networkLimit)

      // the /64 the client is grouped into is not fully covered by the configured /80
      limitFor(overrides, "2001:db8:0:0:0:0:0:0/64") should be(empty)
    }

    "match IPv4-mapped IPv6 addresses like the plain IPv4 address" in {
      val overrides = perClientIp("192.0.2.0/24" -> networkLimit)

      limitFor(overrides, "::ffff:192.0.2.1") should be(Some(networkLimit))
    }

    "match everything for a zero length prefix" in {
      val overrides = perClientIp("0.0.0.0/0" -> networkLimit)

      limitFor(overrides, "8.8.8.8") should be(Some(networkLimit))
      // does not apply to IPv6
      limitFor(overrides, "2001:db8::1") should be(empty)
    }

    "not match values that are not IP addresses" in {
      val overrides = perClientIp("10.0.0.0/8" -> networkLimit)

      limitFor(overrides, "not-an-ip") should be(empty)
      limitFor(overrides, "") should be(empty)
      // must not do a DNS lookup
      limitFor(overrides, "localhost") should be(empty)
    }

    "not match anything without overrides" in {
      limitFor(PerAttributeRateLimitConfig(), "10.1.2.3") should be(empty)
    }

    "normalize the configured network" in {
      IpCidr.tryParse("10.1.2.3/8").toString should be("10.0.0.0/8")
      IpCidr.tryParse("10.1.2.3").toString should be("10.1.2.3/32")
      IpCidr.tryParse("2001:db8:1:2:3:4:5:6/32").toString should be("2001:db8:0:0:0:0:0:0/32")
    }

    "reject invalid configurations" in {
      forAll(
        Seq(
          "not-an-ip/8",
          "10.0.0.0/33",
          "10.0.0.0/-1",
          "10.0.0.0/eight",
          "2001:db8::/129",
          "10.0.0.0/8/16",
          "",
        )
      ) { cidr =>
        val config = perClientIp(cidr -> networkLimit)
        a[IllegalArgumentException] should be thrownBy IpCidrRateLimits.tryValidate(config)
        a[IllegalArgumentException] should be thrownBy IpCidrRateLimits
          .matchClientIp(config)("10.1.2.3")
      }
    }

    "return a reusable matcher for a config" in {
      val matcher =
        IpCidrRateLimits.matchClientIp(perClientIp("10.0.0.0/8" -> networkLimit))

      matcher("10.1.2.3") should be(Some(networkLimit))
      matcher("10.4.5.6") should be(Some(networkLimit))
      matcher("11.0.0.1") should be(empty)
    }
  }

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

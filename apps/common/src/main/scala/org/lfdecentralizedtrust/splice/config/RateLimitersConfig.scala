// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import org.lfdecentralizedtrust.splice.util.{
  PerAttributeRateLimitConfig,
  SpliceRateLimitConfig,
  SpliceRateLimiter,
}

/** An overall rate limit that additionally limits per client IP.
  *
  * The `attributeOverrides` of `perClientIp` (`ip-overrides` in the config) are keyed by an IP
  * network in CIDR notation (a bare IP address denotes a single host), e.g.
  * `{ "10.0.0.0/8" = { rate-per-second = 100 } }`.
  */
case class PerClientIpRateLimitConfig(
    enabled: Boolean = true,
    ratePerSecond: Double,
    sustainedRatePerSecond: Option[Double] = None,
    sustainedWindowSeconds: Long = SpliceRateLimiter.DefaultSustainedWindowSeconds,
    perClientIp: PerAttributeRateLimitConfig = PerAttributeRateLimitConfig.disabled,
) extends SpliceRateLimitConfig

case class RateLimitersConfig(
    /** Overall rate limiter applied per operation. Used when there is no operation-specific override
      * in `rateLimiters`. The embedded `perClientIp` limiter is disabled by default; enable it to
      * additionally limit per client IP.
      */
    default: PerClientIpRateLimitConfig = PerClientIpRateLimitConfig(ratePerSecond = 200),
    /** Per-operation overrides of the overall `default` rate limiter. */
    rateLimiters: Map[String, PerClientIpRateLimitConfig] = Map.empty,
    global: PerClientIpRateLimitConfig = RateLimitersConfig.DefaultGlobal,
    /** Names of the HTTP headers from which the client IP used for per-client-IP rate limiting is
      * extracted, in order of precedence: the first header that is present and whose value (or, for
      * comma separated lists such as `X-Forwarded-For`, whose first entry) parses as an IP literal
      * is used. Set to an empty list to disable per-client-IP rate limiting.
      *
      * Note that the default headers are client-controlled and can hence be spoofed unless they are
      * overwritten by infrastructure the client cannot bypass. In deployments with a trusted reverse
      * proxy, configure the (non-spoofable) header set by that proxy instead, e.g.
      * `["x-envoy-external-address"]` behind an Envoy proxy.
      */
    clientIpHeaders: Seq[String] = RateLimitersConfig.DefaultClientIpHeaders,
) {
  def forRateLimiter(name: String): PerClientIpRateLimitConfig =
    rateLimiters.getOrElse(name, default)
}

object RateLimitersConfig {

  /** The commonly used client IP headers, in order of precedence. Both are set by clients or
    * reverse proxies and are hence only trustworthy if a proxy the client cannot bypass overwrites
    * them.
    */
  val DefaultClientIpHeaders: Seq[String] = Seq("x-forwarded-for", "x-real-ip")

  private val DefaultGlobal: PerClientIpRateLimitConfig =
    PerClientIpRateLimitConfig(
      ratePerSecond = 200,
      perClientIp = PerAttributeRateLimitConfig(),
    )
}

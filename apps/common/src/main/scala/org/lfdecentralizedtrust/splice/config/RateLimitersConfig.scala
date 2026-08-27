// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import org.lfdecentralizedtrust.splice.util.{PerAttributeRateLimitConfig, SpliceRateLimitConfig}

case class RateLimitersConfig(
    /** Overall rate limiter applied per operation. Used when there is no operation-specific override
      * in `rateLimiters`. The embedded `perClientIp` limiter is disabled by default; enable it to
      * additionally limit per client IP.
      */
    default: SpliceRateLimitConfig.WithPerClientIp =
      SpliceRateLimitConfig.WithPerClientIp(ratePerSecond = 200),
    /** Per-operation overrides of the overall `default` rate limiter. */
    rateLimiters: Map[String, SpliceRateLimitConfig.WithPerClientIp] = Map.empty,
    global: SpliceRateLimitConfig.WithPerClientIp = RateLimitersConfig.DefaultGlobal,
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
  def forRateLimiter(name: String): SpliceRateLimitConfig.WithPerClientIp =
    rateLimiters.getOrElse(name, default)
}

object RateLimitersConfig {

  /** The commonly used client IP headers, in order of precedence. Both are set by clients or
    * reverse proxies and are hence only trustworthy if a proxy the client cannot bypass overwrites
    * them.
    */
  val DefaultClientIpHeaders: Seq[String] = Seq("x-forwarded-for", "x-real-ip")

  private val DefaultGlobal: SpliceRateLimitConfig.WithPerClientIp =
    SpliceRateLimitConfig.WithPerClientIp(
      ratePerSecond = 200,
      perClientIp = PerAttributeRateLimitConfig(),
    )
}

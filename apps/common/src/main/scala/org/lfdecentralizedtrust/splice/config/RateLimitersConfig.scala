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
    /** Name of the HTTP header set by a trusted reverse proxy that carries the real client IP. This header must be set - and any
      * client-provided value overwritten - by infrastructure the client cannot bypass, otherwise it
      * can be spoofed. When present and parseable as an IP literal it takes precedence over the
      * client-controlled `X-Forwarded-For`/`X-Real-Ip` headers. Set to an empty string to disable
      * trusting a proxy header and only rely on `X-Forwarded-For`/`X-Real-Ip`/the remote address.
      */
    trustedClientIpHeader: String = RateLimitersConfig.DefaultTrustedClientIpHeader,
) {
  def forRateLimiter(name: String): SpliceRateLimitConfig.WithPerClientIp =
    rateLimiters.getOrElse(name, default)
}

object RateLimitersConfig {

  /** Header set by the Envoy sidecar/ingress (Istio) to the trusted external client address that
    * Envoy computes from its trusted-hops configuration.
    */
  val DefaultTrustedClientIpHeader: String = "x-envoy-external-address"

  private val DefaultGlobal: SpliceRateLimitConfig.WithPerClientIp =
    SpliceRateLimitConfig.WithPerClientIp(
      ratePerSecond = 200,
      perClientIp = PerAttributeRateLimitConfig(),
    )
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import org.lfdecentralizedtrust.splice.util.{PerAttributeRateLimitConfig, SpliceRateLimitConfig}

case class RateLimitersConfig(
    /** Overall rate limiter applied per operation. Used when there is no operation-specific override
      * in `rateLimiters`. The embedded `perClientIp` limiter is disabled by default; enable it to
      * additionally limit per client IP.
      */
    default: SpliceRateLimitConfig.WithPerClientIp,
    /** Per-operation overrides of the overall `default` rate limiter. */
    rateLimiters: Map[String, SpliceRateLimitConfig.WithPerClientIp],
    global: SpliceRateLimitConfig.WithPerClientIp = RateLimitersConfig.DefaultGlobal,
) {
  def forRateLimiter(name: String): SpliceRateLimitConfig.WithPerClientIp =
    rateLimiters.getOrElse(name, default)
}

object RateLimitersConfig {

  private val DefaultGlobal: SpliceRateLimitConfig.WithPerClientIp =
    SpliceRateLimitConfig.WithPerClientIp(
      ratePerSecond = 200,
      perClientIp = PerAttributeRateLimitConfig(),
    )
}

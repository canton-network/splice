// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

/**
 * Mirrors the part of the Splice app rate limiting config (`RateLimitersConfig` in
 * `apps/common/src/main/scala/org/lfdecentralizedtrust/splice/config/RateLimitersConfig.scala`)
 * that is published per network.
 *
 * Only `global` is published: it is the limit that protects the node as a whole and is the same on
 * all networks. The per-operation limits (`default`, `rate-limiters`) are tuned together with the
 * app and keep the values shipped in the app's `app.conf`.
 *
 * The keys are the keys of the app config (kebab-case), not the Scala field names, so that the
 * published section can be passed to the app verbatim: HOCON is a superset of JSON, so it is
 * rendered into `ADDITIONAL_CONFIG_SCAN_RATE_LIMITS` without any translation. That keeps the file
 * usable as-is by operators who do not deploy with the Splice Pulumi code.
 */
const SimpleRateLimitSchema = z
  .object({
    enabled: z.boolean().optional(),
    'rate-per-second': z.number().positive(),
    'sustained-rate-per-second': z.number().positive().optional(),
    'sustained-window-seconds': z.number().int().positive().optional(),
  })
  .strict();
/** Mirrors `PerAttributeRateLimitConfig`, used for the per-client-IP limit. */
const PerClientIpSchema = z
  .object({
    enabled: z.boolean().optional(),
    limit: SimpleRateLimitSchema.optional(),
    'max-attribute-values': z.number().int().positive().optional(),
    /**
     * Keyed by an IP network in CIDR notation (a bare IP address denotes a single host).
     * Named `ip-overrides` rather than `attribute-overrides`, see the `ConfigFieldMapping` for
     * `PerAttributeRateLimitConfig` in `SpliceConfig.scala`.
     */
    'ip-overrides': z.record(z.string().min(1), SimpleRateLimitSchema).optional(),
  })
  .strict();

export const GlobalRateLimitSchema = SimpleRateLimitSchema.extend({
  'per-client-ip': PerClientIpSchema.optional(),
}).strict();

export const AppRateLimitsSchema = z
  .object({
    global: GlobalRateLimitSchema,
  })
  .strict();

export const SpliceRateLimitsSchema = z
  .object({
    scan: AppRateLimitsSchema.optional(),
  })
  .strict();

export type SpliceRateLimits = z.infer<typeof SpliceRateLimitsSchema>;

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { isIP } from 'net';
import { z } from 'zod';

export const BucketRateLimitSchema = z.object({
  maxTokens: z.number(),
  tokensPerFill: z.number(),
  fillInterval: z.string(),
});

const Ipv4AddressSchema = z.string().refine(ip => isIP(ip) === 4, {
  message: 'Expected IPv4 address',
});

const OverrideSchema = BucketRateLimitSchema.extend({
  ips: z.array(Ipv4AddressSchema).min(1),
});

export const PerIpLimitsSchema = BucketRateLimitSchema.extend({
  overrides: z.record(z.string().min(1), OverrideSchema).optional(),
});

const BucketMatchedRateLimitSchema = BucketRateLimitSchema.extend({
  type: z.literal('limited'),
  perIpLimits: PerIpLimitsSchema.optional(),
});

export const BannedSchema = z.object({
  type: z.literal('banned'),
});

export const UnlimitedSchema = z.object({
  type: z.literal('unlimited'),
});

export const RateLimitConfigSchema = z.discriminatedUnion('type', [
  BucketMatchedRateLimitSchema,
  BannedSchema,
  UnlimitedSchema,
]);

export type ExternalRateLimit = z.infer<typeof RateLimitSchema>;

export const RateLimitSchema = z.object({
  globalLimits: BucketRateLimitSchema,
  rateLimits: z.object({}).catchall(
    z.intersection(
      z.object({
        name: z.string(),
      }),
      RateLimitConfigSchema
    )
  ),
});

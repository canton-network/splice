// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { isIP } from 'net';
import { z } from 'zod';

export const BucketRateLimitSchema = z.object({
  maxTokens: z.number(),
  tokensPerFill: z.number(),
  fillInterval: z.string(),
});

const IpRangeSchema = z.string().refine(
  cidr => {
    const parts = cidr.split('/');
    if (parts.length !== 2) {
      return false;
    }
    const [network, prefixStr] = parts;
    if (isIP(network) !== 4) {
      return false;
    }
    const prefix = Number(prefixStr);
    return Number.isInteger(prefix) && prefix >= 0 && prefix <= 32;
  },
  {
    message:
      'Expected IPv4 CIDR (e.g., 192.168.0.0/24). Single IPs must be specified as x.x.x.x/32',
  }
);

const IpRangeOverrideSchema = BucketRateLimitSchema.extend({
  ipRanges: z.array(IpRangeSchema).min(1),
});

export const PerIpRangeLimitSchema = BucketRateLimitSchema.extend({
  overrides: z.record(z.string().min(1), IpRangeOverrideSchema).optional(),
});

const BucketMatchedRateLimitSchema = BucketRateLimitSchema.extend({
  type: z.literal('limited'),
  perIpRangeLimit: PerIpRangeLimitSchema.optional(),
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

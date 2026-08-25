// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@jest/globals';

import { RateLimitSchema } from './rateLimitSchema';

const validConfig = {
  globalLimits: {
    maxTokens: 1000,
    tokensPerFill: 1000,
    fillInterval: '60s',
  },
  rateLimits: {
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      maxTokens: 720,
      tokensPerFill: 720,
      fillInterval: '60s',
      perIpLimits: {
        maxTokens: 120,
        tokensPerFill: 120,
        fillInterval: '60s',
      },
    },
  },
};

test('RateLimitSchema accepts config without overrides', () => {
  expect(() => RateLimitSchema.parse(validConfig)).not.toThrow();
});

test('RateLimitSchema accepts named overrides with ips', () => {
  const config = {
    ...validConfig,
    rateLimits: {
      '/registry/metadata/v1/info': {
        ...validConfig.rateLimits['/registry/metadata/v1/info'],
        perIpLimits: {
          ...validConfig.rateLimits['/registry/metadata/v1/info'].perIpLimits,
          overrides: {
            'single-validator': {
              ips: ['192.68.78.50'],
              maxTokens: 220,
              tokensPerFill: 220,
              fillInterval: '60s',
            },
            'multi-validators': {
              ips: ['192.68.78.51', '192.68.78.52'],
              maxTokens: 250,
              tokensPerFill: 250,
              fillInterval: '60s',
            },
          },
        },
      },
    },
  };
  expect(() => RateLimitSchema.parse(config)).not.toThrow();
});

test('RateLimitSchema rejects override without ips', () => {
  const config = {
    ...validConfig,
    rateLimits: {
      '/registry/metadata/v1/info': {
        ...validConfig.rateLimits['/registry/metadata/v1/info'],
        perIpLimits: {
          ...validConfig.rateLimits['/registry/metadata/v1/info'].perIpLimits,
          overrides: {
            '192.68.78.50': {
              maxTokens: 220,
              tokensPerFill: 220,
              fillInterval: '60s',
            },
          },
        },
      },
    },
  };
  expect(() => RateLimitSchema.parse(config)).toThrow();
});

test('RateLimitSchema rejects non-IPv4 addresses in ips', () => {
  const config = {
    ...validConfig,
    rateLimits: {
      '/registry/metadata/v1/info': {
        ...validConfig.rateLimits['/registry/metadata/v1/info'],
        perIpLimits: {
          ...validConfig.rateLimits['/registry/metadata/v1/info'].perIpLimits,
          overrides: {
            'multi-validators': {
              ips: ['2001:db8::1'],
              maxTokens: 250,
              tokensPerFill: 250,
              fillInterval: '60s',
            },
          },
        },
      },
    },
  };
  expect(() => RateLimitSchema.parse(config)).toThrow();
});

test('RateLimitSchema rejects empty override ips array', () => {
  const config = {
    ...validConfig,
    rateLimits: {
      '/registry/metadata/v1/info': {
        ...validConfig.rateLimits['/registry/metadata/v1/info'],
        perIpLimits: {
          ...validConfig.rateLimits['/registry/metadata/v1/info'].perIpLimits,
          overrides: {
            'empty-group': {
              ips: [],
              maxTokens: 250,
              tokensPerFill: 250,
              fillInterval: '60s',
            },
          },
        },
      },
    },
  };
  expect(() => RateLimitSchema.parse(config)).toThrow();
});

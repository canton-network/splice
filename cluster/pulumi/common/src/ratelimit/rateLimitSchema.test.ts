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
      perIpRangeLimit: {
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

test('RateLimitSchema accepts named overrides with ipRanges', () => {
  const config = {
    ...validConfig,
    rateLimits: {
      '/registry/metadata/v1/info': {
        ...validConfig.rateLimits['/registry/metadata/v1/info'],
        perIpRangeLimit: {
          ...validConfig.rateLimits['/registry/metadata/v1/info'].perIpRangeLimit,
          overrides: {
            'single-validator': {
              ipRanges: ['192.68.78.50/32'],
              maxTokens: 220,
              tokensPerFill: 220,
              fillInterval: '60s',
            },
            'multi-validators': {
              ipRanges: ['192.68.78.0/24', '192.68.79.0/24'],
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

test('RateLimitSchema rejects override without ipRanges', () => {
  const config = {
    ...validConfig,
    rateLimits: {
      '/registry/metadata/v1/info': {
        ...validConfig.rateLimits['/registry/metadata/v1/info'],
        perIpRangeLimit: {
          ...validConfig.rateLimits['/registry/metadata/v1/info'].perIpRangeLimit,
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

test('RateLimitSchema rejects invalid IP ranges in ipRanges', () => {
  const config = {
    ...validConfig,
    rateLimits: {
      '/registry/metadata/v1/info': {
        ...validConfig.rateLimits['/registry/metadata/v1/info'],
        perIpRangeLimit: {
          ...validConfig.rateLimits['/registry/metadata/v1/info'].perIpRangeLimit,
          overrides: {
            'multi-validators': {
              ipRanges: ['2001:db8::1/128'],
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

test('RateLimitSchema rejects empty override ipRanges array', () => {
  const config = {
    ...validConfig,
    rateLimits: {
      '/registry/metadata/v1/info': {
        ...validConfig.rateLimits['/registry/metadata/v1/info'],
        perIpRangeLimit: {
          ...validConfig.rateLimits['/registry/metadata/v1/info'].perIpRangeLimit,
          overrides: {
            'empty-group': {
              ipRanges: [],
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

test('RateLimitSchema rejects invalid prefix length in ipRanges', () => {
  const config = {
    ...validConfig,
    rateLimits: {
      '/registry/metadata/v1/info': {
        ...validConfig.rateLimits['/registry/metadata/v1/info'],
        perIpRangeLimit: {
          ...validConfig.rateLimits['/registry/metadata/v1/info'].perIpRangeLimit,
          overrides: {
            bad: {
              ipRanges: ['192.68.78.0/99'],
              maxTokens: 5000,
              tokensPerFill: 5000,
              fillInterval: '60s',
            },
          },
        },
      },
    },
  };
  expect(() => RateLimitSchema.parse(config)).toThrow();
});

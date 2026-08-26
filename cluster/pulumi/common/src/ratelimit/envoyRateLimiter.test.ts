// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { expect, jest, test } from '@jest/globals';

import {
  buildRateLimitActions,
  buildRateLimitDescriptors,
  parseFillIntervalMs,
  validateIpLimits,
  validateTokenBuckets,
} from './envoyRateLimiter';

jest.mock('@canton-network/splice-pulumi-common/src/config/envConfig', () => ({
  __esModule: true,
  spliceEnvConfig: {
    requireEnv() {
      return 'dummy';
    },
  },
}));

const baseLimits = {
  maxTokens: 720,
  tokensPerFill: 720,
  fillInterval: '60s',
};

const perIpLimits = {
  maxTokens: 120,
  tokensPerFill: 120,
  fillInterval: '60s',
};

test('buildRateLimitDescriptors generates per-endpoint and generic per-IP descriptors', () => {
  const descriptors = buildRateLimitDescriptors({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpLimits,
    },
  });

  expect(descriptors).toHaveLength(2);
  expect(descriptors[0]).toEqual({
    entries: [{ key: 'header_match', value: 'registry-metadata-info' }],
    token_bucket: {
      max_tokens: 720,
      tokens_per_fill: 720,
      fill_interval: '60s',
    },
  });
  expect(descriptors[1]).toEqual({
    entries: [
      { key: 'header_match', value: 'registry-metadata-info' },
      { key: 'masked_remote_address' },
    ],
    token_bucket: {
      max_tokens: 120,
      tokens_per_fill: 120,
      fill_interval: '60s',
    },
  });
});

test('buildRateLimitDescriptors emits named IP overrides before generic per-IP descriptor', () => {
  const descriptors = buildRateLimitDescriptors({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpLimits: {
        ...perIpLimits,
        overrides: {
          'single-validator': {
            ips: ['192.68.78.50'],
            maxTokens: 220,
            tokensPerFill: 220,
            fillInterval: '60s',
          },
        },
      },
    },
  });

  expect(descriptors).toHaveLength(3);
  expect(descriptors[0]).toEqual(
    expect.objectContaining({
      entries: [{ key: 'header_match', value: 'registry-metadata-info' }],
    })
  );
  expect(descriptors[1]).toEqual({
    entries: [
      { key: 'header_match', value: 'registry-metadata-info' },
      { key: 'masked_remote_address', value: '192.68.78.50/32' },
    ],
    token_bucket: {
      max_tokens: 220,
      tokens_per_fill: 220,
      fill_interval: '60s',
    },
  });
  expect(descriptors[2]).toEqual(
    expect.objectContaining({
      entries: [
        { key: 'header_match', value: 'registry-metadata-info' },
        { key: 'masked_remote_address' },
      ],
    })
  );
});

test('buildRateLimitDescriptors emits descriptors for named overrides with multiple ips', () => {
  const descriptors = buildRateLimitDescriptors({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpLimits: {
        ...perIpLimits,
        overrides: {
          'multi-validators': {
            ips: ['192.68.78.51', '192.68.78.52'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
        },
      },
    },
  });

  expect(descriptors).toHaveLength(4);
  expect(descriptors[1]).toEqual({
    entries: [
      { key: 'header_match', value: 'registry-metadata-info' },
      { key: 'masked_remote_address', value: '192.68.78.51/32' },
    ],
    token_bucket: {
      max_tokens: 250,
      tokens_per_fill: 250,
      fill_interval: '60s',
    },
  });
  expect(descriptors[2]).toEqual({
    entries: [
      { key: 'header_match', value: 'registry-metadata-info' },
      { key: 'masked_remote_address', value: '192.68.78.52/32' },
    ],
    token_bucket: {
      max_tokens: 250,
      tokens_per_fill: 250,
      fill_interval: '60s',
    },
  });
});

test('buildRateLimitActions emits per-endpoint and per-IP actions', () => {
  const actions = buildRateLimitActions({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpLimits,
    },
  });

  expect(actions).toHaveLength(2);
  expect(actions[0]).toEqual({
    actions: [
      {
        header_value_match: {
          descriptor_value: 'registry-metadata-info',
          expect_match: true,
          headers: [
            {
              name: ':path',
              string_match: {
                prefix: '/registry/metadata/v1/info',
                ignore_case: true,
              },
            },
          ],
        },
      },
    ],
  });
  expect(actions[1]).toEqual({
    actions: [
      {
        header_value_match: {
          descriptor_value: 'registry-metadata-info',
          expect_match: true,
          headers: [
            {
              name: ':path',
              string_match: {
                prefix: '/registry/metadata/v1/info',
                ignore_case: true,
              },
            },
          ],
        },
      },
      {
        // the raw x-forwarded-for header must not be used, it is attacker controlled
        masked_remote_address: {
          v4_prefix_mask_len: 32,
          v6_prefix_mask_len: 128,
        },
      },
    ],
  });
});

test('validateIpLimits throws on duplicate IP between two named overrides', () => {
  expect(() =>
    validateIpLimits('/registry/metadata/v1/info', {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpLimits: {
        ...perIpLimits,
        overrides: {
          'group-a': {
            ips: ['192.68.78.50', '192.68.78.51'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
          'group-b': {
            ips: ['192.68.78.51'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
        },
      },
    })
  ).toThrow("192.68.78.51 (in override 'group-b')");
});

test('validateIpLimits accepts unique IPs across named overrides', () => {
  expect(() =>
    validateIpLimits('/registry/metadata/v1/info', {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpLimits: {
        ...perIpLimits,
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
    })
  ).not.toThrow();
});

test('parseFillIntervalMs parses protobuf durations and rejects other formats', () => {
  expect(parseFillIntervalMs('60s', 'ctx')).toEqual(60000);
  expect(parseFillIntervalMs('0.5s', 'ctx')).toEqual(500);
  expect(() => parseFillIntervalMs('500ms', 'ctx')).toThrow('invalid fillInterval');
  expect(() => parseFillIntervalMs('1m', 'ctx')).toThrow('invalid fillInterval');
});

test('validateTokenBuckets accepts intervals that are multiples of the global interval', () => {
  expect(() =>
    validateTokenBuckets(baseLimits, {
      '/api/scan/v0/acs': {
        name: 'acs',
        type: 'limited',
        maxTokens: 500,
        tokensPerFill: 500,
        fillInterval: '120s',
        perIpLimits,
      },
    })
  ).not.toThrow();
});

test('validateTokenBuckets rejects intervals that envoy would NACK', () => {
  expect(() =>
    validateTokenBuckets(baseLimits, {
      '/api/scan/v0/acs': {
        name: 'acs',
        type: 'limited',
        maxTokens: 500,
        tokensPerFill: 500,
        fillInterval: '90s',
      },
    })
  ).toThrow('must be a multiple of the globalLimits fillInterval');

  // below envoy's 50ms minimum
  expect(() =>
    validateTokenBuckets(
      { maxTokens: 1, tokensPerFill: 1, fillInterval: '0.01s' },
      {
        '/api/scan/v0/acs': {
          name: 'acs',
          type: 'limited',
          maxTokens: 500,
          tokensPerFill: 500,
          fillInterval: '60s',
        },
      }
    )
  ).toThrow('below the 50ms minimum');

  // per-IP overrides are validated as well
  expect(() =>
    validateTokenBuckets(baseLimits, {
      '/api/scan/v0/acs': {
        name: 'acs',
        type: 'limited',
        ...baseLimits,
        perIpLimits: {
          ...perIpLimits,
          overrides: {
            'single-validator': {
              ips: ['192.68.78.50'],
              maxTokens: 220,
              tokensPerFill: 220,
              fillInterval: '90s',
            },
          },
        },
      },
    })
  ).toThrow("perIpLimits override 'single-validator'");
});

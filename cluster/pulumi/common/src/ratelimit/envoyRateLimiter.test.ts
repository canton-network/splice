// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { expect, jest, test } from '@jest/globals';

import {
  buildRateLimitActions,
  buildRateLimitDescriptors,
  validateCidrLimits,
  validateIpLimits,
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
    entries: [{ key: 'header_match', value: 'registry-metadata-info' }, { key: 'client_ip' }],
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
      { key: 'client_ip', value: '192.68.78.50' },
    ],
    token_bucket: {
      max_tokens: 220,
      tokens_per_fill: 220,
      fill_interval: '60s',
    },
  });
  expect(descriptors[2]).toEqual(
    expect.objectContaining({
      entries: [{ key: 'header_match', value: 'registry-metadata-info' }, { key: 'client_ip' }],
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
      { key: 'client_ip', value: '192.68.78.51' },
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
      { key: 'client_ip', value: '192.68.78.52' },
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
        request_headers: {
          descriptor_key: 'client_ip',
          header_name: 'x-forwarded-for',
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

test('buildRateLimitDescriptors emits per-CIDR override and fallback descriptors', () => {
  const descriptors = buildRateLimitDescriptors({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perCidrLimits: {
        maxTokens: 500,
        tokensPerFill: 500,
        fillInterval: '60s',
        overrides: {
          office: {
            cidrs: ['192.68.78.0/24'],
            maxTokens: 1000,
            tokensPerFill: 1000,
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
      { key: 'remote_address_match', value: 'office' },
    ],
    token_bucket: {
      max_tokens: 1000,
      tokens_per_fill: 1000,
      fill_interval: '60s',
    },
  });
  expect(descriptors[2]).toEqual({
    entries: [
      { key: 'header_match', value: 'registry-metadata-info' },
      { key: 'remote_address_match', value: 'per-cidr-default' },
    ],
    token_bucket: {
      max_tokens: 500,
      tokens_per_fill: 500,
      fill_interval: '60s',
    },
  });
});

test('buildRateLimitActions emits per-CIDR remote_address_match actions', () => {
  const actions = buildRateLimitActions({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perCidrLimits: {
        maxTokens: 500,
        tokensPerFill: 500,
        fillInterval: '60s',
        overrides: {
          office: {
            cidrs: ['192.68.78.0/24'],
            maxTokens: 1000,
            tokensPerFill: 1000,
            fillInterval: '60s',
          },
        },
      },
    },
  });

  expect(actions).toHaveLength(3);
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
        remote_address_match: {
          descriptor_value: 'office',
          address_matcher: {
            cidr_ranges: [
              {
                address_prefix: '192.68.78.0',
                prefix_len: { value: 24 },
              },
            ],
          },
        },
      },
    ],
  });
  expect(actions[2]).toEqual({
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
        remote_address_match: {
          descriptor_value: 'per-cidr-default',
          address_matcher: {
            cidr_ranges: [
              {
                address_prefix: '192.68.78.0',
                prefix_len: { value: 24 },
              },
            ],
            invert_match: true,
          },
        },
      },
    ],
  });
});

test('buildRateLimitDescriptors shares one bucket across multiple CIDRs in the same override', () => {
  const descriptors = buildRateLimitDescriptors({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perCidrLimits: {
        maxTokens: 500,
        tokensPerFill: 500,
        fillInterval: '60s',
        overrides: {
          'validator-net': {
            cidrs: ['1.2.3.4/24', '5.6.7.8/24'],
            maxTokens: 1000,
            tokensPerFill: 1000,
            fillInterval: '60s',
          },
        },
      },
    },
  });

  expect(descriptors).toHaveLength(3);
  expect(descriptors[1]).toEqual({
    entries: [
      { key: 'header_match', value: 'registry-metadata-info' },
      { key: 'remote_address_match', value: 'validator-net' },
    ],
    token_bucket: {
      max_tokens: 1000,
      tokens_per_fill: 1000,
      fill_interval: '60s',
    },
  });
});

test('buildRateLimitActions uses a single shared remote_address_match for multiple CIDRs in the same override', () => {
  const actions = buildRateLimitActions({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perCidrLimits: {
        maxTokens: 500,
        tokensPerFill: 500,
        fillInterval: '60s',
        overrides: {
          'validator-net': {
            cidrs: ['1.2.3.4/24', '5.6.7.8/24'],
            maxTokens: 1000,
            tokensPerFill: 1000,
            fillInterval: '60s',
          },
        },
      },
    },
  });

  expect(actions).toHaveLength(3);
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
        remote_address_match: {
          descriptor_value: 'validator-net',
          address_matcher: {
            cidr_ranges: [
              {
                address_prefix: '1.2.3.4',
                prefix_len: { value: 24 },
              },
              {
                address_prefix: '5.6.7.8',
                prefix_len: { value: 24 },
              },
            ],
          },
        },
      },
    ],
  });
  expect(actions[2]).toEqual(
    expect.objectContaining({
      actions: [
        expect.objectContaining({}),
        {
          remote_address_match: {
            descriptor_value: 'per-cidr-default',
            address_matcher: {
              cidr_ranges: [
                {
                  address_prefix: '1.2.3.4',
                  prefix_len: { value: 24 },
                },
                {
                  address_prefix: '5.6.7.8',
                  prefix_len: { value: 24 },
                },
              ],
              invert_match: true,
            },
          },
        },
      ],
    })
  );
});

test('validateCidrLimits throws on overlapping CIDRs', () => {
  expect(() =>
    validateCidrLimits('/registry/metadata/v1/info', {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perCidrLimits: {
        ...perIpLimits,
        overrides: {
          'group-a': {
            cidrs: ['192.68.78.0/24'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
          'group-b': {
            cidrs: ['192.68.78.128/25'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
        },
      },
    })
  ).toThrow("192.68.78.0/24 (in override 'group-a') and 192.68.78.128/25 (in override 'group-b')");
});

test('validateCidrLimits throws when one CIDR is fully contained within another', () => {
  expect(() =>
    validateCidrLimits('/registry/metadata/v1/info', {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perCidrLimits: {
        ...perIpLimits,
        overrides: {
          'group-a': {
            cidrs: ['192.68.78.0/24'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
          'group-b': {
            cidrs: ['192.68.78.0/25'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
        },
      },
    })
  ).toThrow("192.68.78.0/24 (in override 'group-a') and 192.68.78.0/25 (in override 'group-b')");
});

test('validateCidrLimits accepts non-overlapping CIDRs', () => {
  expect(() =>
    validateCidrLimits('/registry/metadata/v1/info', {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perCidrLimits: {
        ...perIpLimits,
        overrides: {
          'group-a': {
            cidrs: ['192.68.78.0/25'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
          'group-b': {
            cidrs: ['192.68.78.128/25'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
        },
      },
    })
  ).not.toThrow();
});

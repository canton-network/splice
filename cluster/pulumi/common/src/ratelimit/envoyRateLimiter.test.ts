// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { expect, jest, test } from '@jest/globals';

import {
  buildRateLimitActions,
  buildRateLimitDescriptors,
  validateIpRangeLimits,
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

const perIpRangeLimit = {
  maxTokens: 120,
  tokensPerFill: 120,
  fillInterval: '60s',
};

test('buildRateLimitDescriptors generates per-endpoint and generic per-IP-range descriptors', () => {
  const descriptors = buildRateLimitDescriptors({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpRangeLimit,
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
      { key: 'remote_address_match', value: 'per-ip-range-default' },
    ],
    token_bucket: {
      max_tokens: 120,
      tokens_per_fill: 120,
      fill_interval: '60s',
    },
  });
});

test('buildRateLimitDescriptors emits named IP-range overrides before generic descriptor', () => {
  const descriptors = buildRateLimitDescriptors({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpRangeLimit: {
        ...perIpRangeLimit,
        overrides: {
          'single-validator': {
            ipRanges: ['192.68.78.50/32'],
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
      { key: 'remote_address_match', value: 'single-validator' },
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
        { key: 'remote_address_match', value: 'per-ip-range-default' },
      ],
    })
  );
});

test('buildRateLimitDescriptors shares one bucket across multiple IP ranges in the same override', () => {
  const descriptors = buildRateLimitDescriptors({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpRangeLimit: {
        ...perIpRangeLimit,
        overrides: {
          'validator-net': {
            ipRanges: ['1.2.3.0/24', '5.6.7.0/24'],
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

test('buildRateLimitActions emits per-endpoint and per-IP-range actions', () => {
  const actions = buildRateLimitActions({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpRangeLimit,
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
        remote_address_match: {
          descriptor_value: 'per-ip-range-default',
          address_matcher: {
            cidr_ranges: [],
            invert_match: true,
          },
        },
      },
    ],
  });
});

test('buildRateLimitActions emits per-IP-range remote_address_match actions', () => {
  const actions = buildRateLimitActions({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpRangeLimit: {
        maxTokens: 500,
        tokensPerFill: 500,
        fillInterval: '60s',
        overrides: {
          office: {
            ipRanges: ['192.68.78.0/24'],
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
          descriptor_value: 'per-ip-range-default',
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

test('buildRateLimitActions uses a single shared remote_address_match for multiple IP ranges in the same override', () => {
  const actions = buildRateLimitActions({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpRangeLimit: {
        maxTokens: 500,
        tokensPerFill: 500,
        fillInterval: '60s',
        overrides: {
          'validator-net': {
            ipRanges: ['1.2.3.0/24', '5.6.7.0/24'],
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
                address_prefix: '1.2.3.0',
                prefix_len: { value: 24 },
              },
              {
                address_prefix: '5.6.7.0',
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
            descriptor_value: 'per-ip-range-default',
            address_matcher: {
              cidr_ranges: [
                {
                  address_prefix: '1.2.3.0',
                  prefix_len: { value: 24 },
                },
                {
                  address_prefix: '5.6.7.0',
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

test('validateIpRangeLimits throws on overlapping IP ranges', () => {
  expect(() =>
    validateIpRangeLimits('/registry/metadata/v1/info', {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpRangeLimit: {
        ...perIpRangeLimit,
        overrides: {
          'group-a': {
            ipRanges: ['192.68.78.0/24'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
          'group-b': {
            ipRanges: ['192.68.78.128/25'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
        },
      },
    })
  ).toThrow("192.68.78.0/24 (in override 'group-a') and 192.68.78.128/25 (in override 'group-b')");
});

test('validateIpRangeLimits throws when one IP range is fully contained within another', () => {
  expect(() =>
    validateIpRangeLimits('/registry/metadata/v1/info', {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpRangeLimit: {
        ...perIpRangeLimit,
        overrides: {
          'group-a': {
            ipRanges: ['192.68.78.0/24'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
          'group-b': {
            ipRanges: ['192.68.78.0/25'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
        },
      },
    })
  ).toThrow("192.68.78.0/24 (in override 'group-a') and 192.68.78.0/25 (in override 'group-b')");
});

test('validateIpRangeLimits accepts non-overlapping IP ranges', () => {
  expect(() =>
    validateIpRangeLimits('/registry/metadata/v1/info', {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpRangeLimit: {
        ...perIpRangeLimit,
        overrides: {
          'group-a': {
            ipRanges: ['192.68.78.0/25'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
          'group-b': {
            ipRanges: ['192.68.78.128/25'],
            maxTokens: 250,
            tokensPerFill: 250,
            fillInterval: '60s',
          },
        },
      },
    })
  ).not.toThrow();
});

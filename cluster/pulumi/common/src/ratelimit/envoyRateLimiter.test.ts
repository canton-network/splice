// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@jest/globals';

import {
  buildEndpointRateLimitDescriptors,
  buildGlobalPerIpRateLimitAction,
  buildGlobalPerIpRateLimitDescriptors,
  buildPerEndpointPerIpRateLimitDescriptors,
  buildRateLimitActions,
  buildRateLimitFilters,
  buildTypedPerFilterConfig,
  globalPerIpRateLimitFilterName,
  globalPerIpRateLimitStatPrefix,
  globalRateLimitFilterName,
  globalRateLimitStatPrefix,
  parseFillIntervalMs,
  perEndpointPerIpRateLimitFilterName,
  perEndpointPerIpRateLimitStatPrefix,
  rateLimiterLabel,
  rateLimiterMetricPrefix,
  rateLimiterMetricRelabelings,
  validateEffectiveRateLimits,
  validateIpLimits,
  validateTokenBuckets,
} from './envoyRateLimiter';

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

const globalLimits = {
  maxTokens: 10000,
  tokensPerFill: 10000,
  fillInterval: '60s',
};

const globalPerIpLimits = {
  maxTokens: 1000,
  tokensPerFill: 1000,
  fillInterval: '60s',
};

test('buildEndpointRateLimitDescriptors generates one bucket per endpoint', () => {
  const descriptors = buildEndpointRateLimitDescriptors({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpLimits,
    },
  });

  expect(descriptors).toEqual([
    {
      entries: [{ key: 'header_match', value: 'registry-metadata-info' }],
      token_bucket: {
        max_tokens: 720,
        tokens_per_fill: 720,
        fill_interval: '60s',
      },
    },
  ]);
});

test('buildPerEndpointPerIpRateLimitDescriptors generates a bucket per endpoint and client IP', () => {
  const descriptors = buildPerEndpointPerIpRateLimitDescriptors({
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpLimits,
    },
  });

  expect(descriptors).toEqual([
    {
      entries: [
        { key: 'header_match', value: 'registry-metadata-info' },
        { key: 'masked_remote_address' },
      ],
      token_bucket: {
        max_tokens: 120,
        tokens_per_fill: 120,
        fill_interval: '60s',
      },
    },
  ]);
});

test('buildPerEndpointPerIpRateLimitDescriptors emits named IP overrides before generic per-IP descriptor', () => {
  const descriptors = buildPerEndpointPerIpRateLimitDescriptors({
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

  expect(descriptors).toHaveLength(2);
  expect(descriptors[0]).toEqual({
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
  expect(descriptors[1]).toEqual(
    expect.objectContaining({
      entries: [
        { key: 'header_match', value: 'registry-metadata-info' },
        { key: 'masked_remote_address' },
      ],
    })
  );
});

test('buildPerEndpointPerIpRateLimitDescriptors emits descriptors for named overrides with multiple ips', () => {
  const descriptors = buildPerEndpointPerIpRateLimitDescriptors({
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

  expect(descriptors).toHaveLength(3);
  expect(descriptors[0]).toEqual({
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
  expect(descriptors[1]).toEqual({
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

test('a request consumes a token from the global, the global per-IP and the per-endpoint buckets', () => {
  const rateLimits = {
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited' as const,
      ...baseLimits,
      perIpLimits,
    },
  };
  const config = buildTypedPerFilterConfig(
    buildRateLimitFilters(globalLimits, globalPerIpLimits, rateLimits)
  );

  // envoy consumes at most one descriptor bucket per filter, so limits that must all be
  // respected are enforced by separate filters
  expect(Object.keys(config)).toEqual([
    globalRateLimitFilterName,
    globalPerIpRateLimitFilterName,
    perEndpointPerIpRateLimitFilterName,
  ]);

  const globalFilter = config[globalRateLimitFilterName] as Record<string, unknown>;
  // the global bucket is consumed even by requests matching a per-endpoint descriptor
  expect(globalFilter.always_consume_default_token_bucket).toBe(true);
  expect(globalFilter.token_bucket).toEqual({
    max_tokens: 10000,
    tokens_per_fill: 10000,
    fill_interval: '60s',
  });
  // the per-endpoint buckets are the only descriptors of the global filter
  expect(globalFilter.descriptors).toEqual([
    {
      entries: [{ key: 'header_match', value: 'registry-metadata-info' }],
      token_bucket: { max_tokens: 720, tokens_per_fill: 720, fill_interval: '60s' },
    },
  ]);

  // every request, including the ones hitting a per-endpoint limit, consumes a token of the
  // bucket of its client IP
  const globalPerIpFilter = config[globalPerIpRateLimitFilterName] as Record<string, unknown>;
  expect(globalPerIpFilter.descriptors).toEqual(
    buildGlobalPerIpRateLimitDescriptors(globalPerIpLimits)
  );
  // the filters whose limits are all expressed as descriptors must not limit anything through
  // their default bucket
  expect(globalPerIpFilter.always_consume_default_token_bucket).toBe(false);
  expect(globalPerIpFilter.token_bucket).toEqual({
    max_tokens: 4294967295,
    tokens_per_fill: 4294967295,
    fill_interval: '60s',
  });

  const perEndpointPerIpFilter = config[perEndpointPerIpRateLimitFilterName] as Record<
    string,
    unknown
  >;
  expect(perEndpointPerIpFilter.always_consume_default_token_bucket).toBe(false);
  expect(perEndpointPerIpFilter.descriptors).toEqual(
    buildPerEndpointPerIpRateLimitDescriptors(rateLimits)
  );
});

test('the filters are distinguishable in the metrics and in the access logs', () => {
  const filters = buildRateLimitFilters(globalLimits, globalPerIpLimits, {
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpLimits,
    },
  });

  // envoy does not label the local rate limit metrics, the stat prefix is the only distinction
  expect(filters.map(f => f.statPrefix)).toEqual([
    globalRateLimitStatPrefix,
    globalPerIpRateLimitStatPrefix,
    perEndpointPerIpRateLimitStatPrefix,
  ]);
  expect(new Set(filters.map(f => f.statPrefix)).size).toEqual(filters.length);

  // and the response header identifies the limit that rejected a request in the access logs
  const config = buildTypedPerFilterConfig(filters);
  const headerValues = filters.map(filter => {
    const filterConfig = config[filter.name] as Record<string, unknown>;
    const headers = filterConfig.response_headers_to_add as {
      header: { key: string; value: string };
    }[];
    expect(headers[0].header.key).toEqual('x-local-rate-limit');
    return headers[0].header.value;
  });
  // the same names are used for the `limiter` metric label, so that a rejection can be correlated
  // between the metrics and the access logs
  expect(headerValues).toEqual(
    filters.map(filter => filter.statPrefix.replace(/^.*_limiter_/, ''))
  );
});

test('the metric relabelings merge the filter metrics into one metric labeled by limiter', () => {
  const [extractLimiter, normalizeName] = rateLimiterMetricRelabelings;

  const relabel = (metric: string) => {
    const limiter = new RegExp(`^${extractLimiter.regex}$`).exec(metric);
    const name = new RegExp(`^${normalizeName.regex}$`).exec(metric);
    return {
      [rateLimiterLabel]: limiter?.[1],
      __name__: name ? `${rateLimiterMetricPrefix}_${name[1]}` : undefined,
    };
  };

  const filters = buildRateLimitFilters(globalLimits, globalPerIpLimits, {
    '/registry/metadata/v1/info': {
      name: 'registry-metadata-info',
      type: 'limited',
      ...baseLimits,
      perIpLimits,
    },
  });

  expect(
    filters.map(filter => relabel(`envoy_${filter.statPrefix}_http_local_rate_limit_enforced`))
  ).toEqual([
    { limiter: 'global', __name__: 'envoy_http_local_rate_limit_enforced' },
    { limiter: 'per_ip', __name__: 'envoy_http_local_rate_limit_enforced' },
    { limiter: 'endpoint_per_ip', __name__: 'envoy_http_local_rate_limit_enforced' },
  ]);
  // all the counters of the filter are covered
  expect(
    ['enabled', 'ok', 'rate_limited', 'enforced'].map(counter =>
      relabel(`envoy_${globalPerIpRateLimitStatPrefix}_http_local_rate_limit_${counter}`)
    )
  ).toEqual(
    ['enabled', 'ok', 'rate_limited', 'enforced'].map(counter => ({
      limiter: 'per_ip',
      __name__: `envoy_http_local_rate_limit_${counter}`,
    }))
  );
  // and unrelated metrics are left alone
  expect(relabel('istio_requests_total')).toEqual({ limiter: undefined, __name__: undefined });
});

test('the per-endpoint per-IP filter is omitted when no endpoint configures per-IP limits', () => {
  const config = buildTypedPerFilterConfig(
    buildRateLimitFilters(globalLimits, globalPerIpLimits, {
      '/registry/metadata/v1/info': {
        name: 'registry-metadata-info',
        type: 'limited',
        ...baseLimits,
      },
    })
  );

  expect(Object.keys(config)).toEqual([globalRateLimitFilterName, globalPerIpRateLimitFilterName]);
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

test('buildGlobalPerIpRateLimitAction keys only on the non-spoofable client address', () => {
  expect(buildGlobalPerIpRateLimitAction()).toEqual({
    actions: [
      {
        masked_remote_address: {
          v4_prefix_mask_len: 32,
          v6_prefix_mask_len: 128,
        },
      },
    ],
  });
});

test('buildGlobalPerIpRateLimitDescriptors emits a wildcard per-IP bucket', () => {
  expect(
    buildGlobalPerIpRateLimitDescriptors({
      maxTokens: 1000,
      tokensPerFill: 1000,
      fillInterval: '60s',
    })
  ).toEqual([
    {
      entries: [{ key: 'masked_remote_address' }],
      token_bucket: {
        max_tokens: 1000,
        tokens_per_fill: 1000,
        fill_interval: '60s',
      },
    },
  ]);
});

test('buildGlobalPerIpRateLimitDescriptors emits named IP overrides before the wildcard bucket', () => {
  const descriptors = buildGlobalPerIpRateLimitDescriptors({
    ...globalPerIpLimits,
    overrides: {
      'multi-validators': {
        ips: ['192.68.78.51', '192.68.78.52'],
        maxTokens: 5000,
        tokensPerFill: 5000,
        fillInterval: '60s',
      },
    },
  });

  expect(descriptors).toEqual([
    {
      entries: [{ key: 'masked_remote_address', value: '192.68.78.51/32' }],
      token_bucket: { max_tokens: 5000, tokens_per_fill: 5000, fill_interval: '60s' },
    },
    {
      entries: [{ key: 'masked_remote_address', value: '192.68.78.52/32' }],
      token_bucket: { max_tokens: 5000, tokens_per_fill: 5000, fill_interval: '60s' },
    },
    {
      entries: [{ key: 'masked_remote_address' }],
      token_bucket: { max_tokens: 1000, tokens_per_fill: 1000, fill_interval: '60s' },
    },
  ]);
});

const envoyFilterArgs = {
  namespace: 'sv-1',
  appLabel: 'scan-app',
  inboundPort: 5012,
  globalLimits: { maxTokens: 10000, tokensPerFill: 10000, fillInterval: '60s' },
  globalPerIpLimits: { maxTokens: 1000, tokensPerFill: 1000, fillInterval: '60s' },
  rateLimits: {
    '/api/scan/v0/acs': {
      name: 'acs',
      type: 'limited' as const,
      ...baseLimits,
      perIpLimits,
    },
  },
};

test('validateEffectiveRateLimits validates the global per-IP limits', () => {
  expect(() =>
    validateEffectiveRateLimits({
      ...envoyFilterArgs,
      globalPerIpLimits: { maxTokens: 1000, tokensPerFill: 1000, fillInterval: '90s' },
    })
  ).toThrow('globalPerIpLimits: fillInterval');
});

test('validateEffectiveRateLimits validates the global per-IP overrides', () => {
  expect(() =>
    validateEffectiveRateLimits({
      ...envoyFilterArgs,
      globalPerIpLimits: {
        ...envoyFilterArgs.globalPerIpLimits,
        overrides: {
          'single-validator': {
            ips: ['192.68.78.50'],
            maxTokens: 5000,
            tokensPerFill: 5000,
            fillInterval: '90s',
          },
        },
      },
    })
  ).toThrow("globalPerIpLimits override 'single-validator'");

  expect(() =>
    validateEffectiveRateLimits({
      ...envoyFilterArgs,
      globalPerIpLimits: {
        ...envoyFilterArgs.globalPerIpLimits,
        overrides: {
          'group-a': {
            ips: ['192.68.78.50'],
            maxTokens: 5000,
            tokensPerFill: 5000,
            fillInterval: '60s',
          },
          'group-b': {
            ips: ['192.68.78.50'],
            maxTokens: 5000,
            tokensPerFill: 5000,
            fillInterval: '60s',
          },
        },
      },
    })
  ).toThrow(
    "globalPerIpLimits: duplicate IPs in per-IP rate limits: 192.68.78.50 (in override 'group-b')"
  );
});

test('validateEffectiveRateLimits rejects reserved descriptor names', () => {
  expect(() =>
    validateEffectiveRateLimits({
      ...envoyFilterArgs,
      rateLimits: {
        '/api/scan/v0/acs': {
          name: 'masked_remote_address',
          type: 'limited' as const,
          ...baseLimits,
        },
      },
    })
  ).toThrow('use reserved name');
});

test('validateIpLimits throws on duplicate IP between two named overrides', () => {
  expect(() =>
    validateIpLimits('/registry/metadata/v1/info', {
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
    })
  ).toThrow("192.68.78.51 (in override 'group-b')");
});

test('validateIpLimits accepts unique IPs across named overrides', () => {
  expect(() =>
    validateIpLimits('/registry/metadata/v1/info', {
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

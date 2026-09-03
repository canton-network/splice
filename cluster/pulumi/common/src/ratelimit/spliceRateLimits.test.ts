// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, test } from '@jest/globals';

import { SpliceRateLimitsSchema } from './spliceRateLimitsSchema';

describe('splice rate limits schema', () => {
  test('preserves every field, so that it overrides the corresponding app default', () => {
    const config = {
      scan: {
        global: {
          enabled: true,
          'rate-per-second': 777,
          'sustained-rate-per-second': 333,
          'sustained-window-seconds': 120,
          'per-client-ip': {
            enabled: true,
            limit: { 'rate-per-second': 42, 'sustained-rate-per-second': 21 },
            'max-attribute-values': 5000,
            'ip-overrides': { '10.0.0.0/8': { 'rate-per-second': 1234 } },
          },
        },
      },
    };
    expect(SpliceRateLimitsSchema.parse(config)).toEqual(config);
  });
  test.each([
    ['the Scala field names instead of the app config keys', { global: { ratePerSecond: 400 } }],
    [
      'attribute-overrides, which the app config calls ip-overrides',
      { global: { 'rate-per-second': 1, 'per-client-ip': { 'attribute-overrides': {} } } },
    ],
    ['an unknown key', { global: { 'rate-per-second': 400, typo: 1 } }],
    ['a limit without a rate', { global: { enabled: true } }],
    ['the per-operation limits, which are not published', { default: { 'rate-per-second': 100 } }],
  ])('rejects %s', (_name, scan) => {
    expect(() => SpliceRateLimitsSchema.parse({ scan })).toThrow();
  });
});

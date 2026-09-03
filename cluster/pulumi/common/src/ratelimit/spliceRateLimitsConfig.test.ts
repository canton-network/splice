// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@jest/globals';

import { scanRateLimitEnvVarsFor } from './spliceRateLimitsConfig';

test('passes the published limits through unchanged, so that they override the app defaults', () => {
  const published = {
    global: {
      enabled: true,
      'rate-per-second': 777,
      'sustained-rate-per-second': 333,
      'sustained-window-seconds': 120,
      'per-client-ip': {
        enabled: true,
        limit: { 'rate-per-second': 42 },
        'max-attribute-values': 5000,
        'ip-overrides': { '10.0.0.0/8': { 'rate-per-second': 1234 } },
      },
    },
  };
  const [envVar] = scanRateLimitEnvVarsFor(published);
  expect(envVar.name).toBe('ADDITIONAL_CONFIG_SCAN_RATE_LIMITS');
  const [path, config] = envVar.value.split(' = ');
  expect(path).toBe('canton.scan-apps.scan-app.parameters.rate-limiting');
  expect(JSON.parse(config)).toEqual(published);
});
test('sets nothing when no limits are published, so that the app defaults apply', () => {
  expect(scanRateLimitEnvVarsFor(undefined)).toEqual([]);
});

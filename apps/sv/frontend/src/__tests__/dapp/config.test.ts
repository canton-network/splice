// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, test } from 'vitest';
import { configScheme, getDappModeConfig, requireOperatorConfig } from '../../utils/config';
import { config as baseConfig } from '../setup/config';

const scanUrl = 'http://scan.localhost:4000/api/scan';
const dappModeEnabled = { enabled: true, scanUrl };
const scanUrlRequired = 'dappMode.scanUrl is required when dappMode.enabled';

const issuesOf = (config: unknown) => {
  const result = configScheme.safeParse(config);
  expect(result.success).toBe(false);
  return result.error?.issues ?? [];
};

// Asserts the exact top-level issue, so a regression to a generic "matches neither mode" error fails.
const expectSingleIssue = (config: unknown, path: PropertyKey[], message: string) =>
  expect(issuesOf(config)).toEqual([expect.objectContaining({ path, message })]);

describe('dappMode config', () => {
  test('absent block means operator mode', () => {
    const parsed = configScheme.parse(baseConfig);
    expect(getDappModeConfig(parsed)).toBeUndefined();
    expect(requireOperatorConfig(parsed).auth).toEqual(baseConfig.auth);
  });
  test('empty block means operator mode', () => {
    const parsed = configScheme.parse({ ...baseConfig, dappMode: {} });
    expect(getDappModeConfig(parsed)).toBeUndefined();
  });
  test('enabled: false means operator mode even with a scanUrl', () => {
    const parsed = configScheme.parse({
      ...baseConfig,
      dappMode: { enabled: false, scanUrl: 'http://scan/api/scan' },
    });
    expect(getDappModeConfig(parsed)).toBeUndefined();
  });
  test('enabled: true yields the scan url', () => {
    const parsed = configScheme.parse({ ...baseConfig, dappMode: dappModeEnabled });
    expect(getDappModeConfig(parsed)).toEqual(dappModeEnabled);
  });
  test('returns the parsed dappMode block itself, not a copy', () => {
    const parsed = configScheme.parse({ ...baseConfig, dappMode: dappModeEnabled });
    expect(getDappModeConfig(parsed)).toBe(parsed.dappMode);
    expect(getDappModeConfig(parsed)).toBe(getDappModeConfig(parsed));
  });
  test('rejects enabled without scanUrl', () => {
    expectSingleIssue(
      { ...baseConfig, dappMode: { enabled: true } },
      ['dappMode', 'scanUrl'],
      scanUrlRequired
    );
    expectSingleIssue(
      { ...baseConfig, dappMode: { enabled: true, scanUrl: '  ' } },
      ['dappMode', 'scanUrl'],
      scanUrlRequired
    );
  });
  test('enabled: true trims and strips trailing slashes from the scan url', () => {
    const parsed = configScheme.parse({
      ...baseConfig,
      dappMode: { enabled: true, scanUrl: `  ${scanUrl}//  ` },
    });
    expect(getDappModeConfig(parsed)).toEqual(dappModeEnabled);
  });
  test('rejects enabled with a scheme-less or non-http scanUrl', () => {
    for (const badUrl of ['scan.localhost/api/scan', 'ftp://scan.localhost/api/scan', 'ftp://x']) {
      expectSingleIssue(
        { ...baseConfig, dappMode: { enabled: true, scanUrl: badUrl } },
        ['dappMode', 'scanUrl'],
        'dappMode.scanUrl must be an absolute http:// or https:// URL'
      );
    }
  });
  test('enabled: true does not validate an empty rs-256 auth block', () => {
    const parsed = configScheme.parse({
      ...baseConfig,
      auth: { algorithm: 'rs-256', authority: '', client_id: '', token_audience: '' },
      dappMode: dappModeEnabled,
    });
    expect(getDappModeConfig(parsed)).toEqual(dappModeEnabled);
    expect('auth' in parsed).toBe(false);
    expect(() => requireOperatorConfig(parsed)).toThrow();
  });
  test('enabled: true does not require an auth block', () => {
    const { auth: _auth, ...withoutAuth } = baseConfig;
    const parsed = configScheme.parse({ ...withoutAuth, dappMode: dappModeEnabled });
    expect(getDappModeConfig(parsed)).toEqual(dappModeEnabled);
  });
  test('enabled: true does not require an SV app url', () => {
    const { services: _services, ...withoutServices } = baseConfig;
    const parsed = configScheme.parse({ ...withoutServices, dappMode: dappModeEnabled });
    expect(getDappModeConfig(parsed)).toEqual(dappModeEnabled);
    expect('services' in parsed).toBe(false);
  });
  test('operator mode requires an SV app url', () => {
    const { services: _services, ...withoutServices } = baseConfig;
    expectSingleIssue(
      withoutServices,
      ['services'],
      'Invalid input: expected object, received undefined'
    );
  });
  test('operator mode requires an auth block', () => {
    const { auth: _auth, ...withoutAuth } = baseConfig;
    expectSingleIssue(
      withoutAuth,
      ['auth'],
      'auth is required in operator mode; for the read-only dApp mode set dappMode.enabled: true and dappMode.scanUrl instead'
    );
  });
  test('operator mode still validates the auth block', () => {
    const issues = issuesOf({
      ...baseConfig,
      auth: { algorithm: 'rs-256', authority: '', client_id: '', token_audience: '' },
    });
    expect(issues.map(i => i.path)).toContainEqual(['auth', 'authority']);
    expect(issues.every(i => i.path[0] === 'auth')).toBe(true);
  });
  test('reports a missing common field directly', () => {
    expectSingleIssue(
      {
        ...baseConfig,
        spliceInstanceNames: { ...baseConfig.spliceInstanceNames, amuletName: undefined },
      },
      ['spliceInstanceNames', 'amuletName'],
      'Invalid input: expected string, received undefined'
    );
  });
  test('reports operator-mode field errors directly', () => {
    expectSingleIssue(
      { ...baseConfig, auth: { ...baseConfig.auth, algorithm: 'nope' } },
      ['auth', 'algorithm'],
      "Invalid discriminator value. Expected 'rs-256' | 'hs-256-unsafe'"
    );
    expect(issuesOf({ ...baseConfig, testAuth: { token_audience: 'aud' } })).toEqual([
      expect.objectContaining({
        path: ['testAuth', 'secret'],
        message: 'Invalid input: expected string, received undefined',
      }),
    ]);
    expectSingleIssue(
      { ...baseConfig, dappMode: { enabled: 'true', scanUrl } },
      ['dappMode', 'enabled'],
      'dappMode.enabled must be true (dApp mode) or false (operator mode)'
    );
  });
});

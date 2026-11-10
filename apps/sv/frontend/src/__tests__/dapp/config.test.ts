// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, test } from 'vitest';
import { configScheme, getDappModeConfig } from '../../utils/config';
import { config as baseConfig } from '../setup/config';

describe('dappMode config', () => {
  test('absent block means operator mode', () => {
    const parsed = configScheme.parse(baseConfig);
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
    const parsed = configScheme.parse({
      ...baseConfig,
      dappMode: { enabled: true, scanUrl: 'http://scan.localhost:4000/api/scan' },
    });
    expect(getDappModeConfig(parsed)).toEqual({ scanUrl: 'http://scan.localhost:4000/api/scan' });
  });
  test('rejects enabled without scanUrl', () => {
    expect(() => configScheme.parse({ ...baseConfig, dappMode: { enabled: true } })).toThrow();
    expect(() =>
      configScheme.parse({ ...baseConfig, dappMode: { enabled: true, scanUrl: '  ' } })
    ).toThrow();
  });
  test('enabled: true strips trailing slashes from the scan url', () => {
    const parsed = configScheme.parse({
      ...baseConfig,
      dappMode: { enabled: true, scanUrl: 'http://scan.localhost:4000/api/scan/' },
    });
    expect(getDappModeConfig(parsed)).toEqual({ scanUrl: 'http://scan.localhost:4000/api/scan' });
  });
  test('rejects enabled with a scheme-less scanUrl', () => {
    expect(() =>
      configScheme.parse({
        ...baseConfig,
        dappMode: { enabled: true, scanUrl: 'scan.localhost/api/scan' },
      })
    ).toThrow();
  });
  test('enabled: true does not validate an empty rs-256 auth block', () => {
    const parsed = configScheme.parse({
      ...baseConfig,
      auth: { algorithm: 'rs-256', authority: '', client_id: '', token_audience: '' },
      dappMode: { enabled: true, scanUrl: 'http://scan.localhost:4000/api/scan' },
    });
    expect(getDappModeConfig(parsed)).toEqual({ scanUrl: 'http://scan.localhost:4000/api/scan' });
    expect(parsed.auth).toBeUndefined();
  });
  test('enabled: true does not require an auth block', () => {
    const { auth: _auth, ...withoutAuth } = baseConfig;
    const parsed = configScheme.parse({
      ...withoutAuth,
      dappMode: { enabled: true, scanUrl: 'http://scan.localhost:4000/api/scan' },
    });
    expect(getDappModeConfig(parsed)).toEqual({ scanUrl: 'http://scan.localhost:4000/api/scan' });
  });
  test('operator mode requires an auth block', () => {
    const { auth: _auth, ...withoutAuth } = baseConfig;
    expect(() => configScheme.parse(withoutAuth)).toThrow(/auth/);
  });
  test('operator mode still validates the auth block', () => {
    expect(() =>
      configScheme.parse({
        ...baseConfig,
        auth: { algorithm: 'rs-256', authority: '', client_id: '', token_audience: '' },
      })
    ).toThrow(/authority/);
  });
});

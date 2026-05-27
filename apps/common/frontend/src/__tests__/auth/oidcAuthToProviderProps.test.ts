// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, test } from 'vitest';

import { Algorithm, AuthConfig } from '../../config/schema';
import { oidcAuthToProviderProps } from '../../utils/auth';

const baseConfig: AuthConfig = {
  algorithm: Algorithm.RS256,
  token_audience: 'test-aud',
  token_scope: 'wallet',
  authority: 'https://idp.example.com/',
  client_id: 'test-client',
  enable_offline_scope: false,
};

const scopes = (s: string) => s.split(' ').filter(Boolean).sort();

describe('oidcAuthToProviderProps scope handling', () => {
  test('omits offline_access when enable_offline_scope is false', () => {
    const props = oidcAuthToProviderProps(baseConfig);
    expect(scopes(props.scope!)).toEqual(['openid', 'wallet']);
  });

  test('includes offline_access when enable_offline_scope is true', () => {
    const props = oidcAuthToProviderProps({ ...baseConfig, enable_offline_scope: true });
    expect(scopes(props.scope!)).toEqual(['offline_access', 'openid', 'wallet']);
  });

  test('omits the openid scope for the daml_ledger_api scope (multi-audience workaround)', () => {
    const props = oidcAuthToProviderProps({ ...baseConfig, token_scope: 'daml_ledger_api' });
    expect(scopes(props.scope!)).toEqual(['daml_ledger_api']);
  });

  test('passes token_audience through as extraQueryParams.audience', () => {
    const props = oidcAuthToProviderProps(baseConfig);
    expect(props.extraQueryParams).toEqual({ audience: 'test-aud' });
  });

  test('does not leak the enable_offline_scope field into the OidcAuthProvider props', () => {
    const props = oidcAuthToProviderProps({ ...baseConfig, enable_offline_scope: true });
    expect((props as Record<string, unknown>).enable_offline_scope).toBeUndefined();
  });
});

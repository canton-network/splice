// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render } from '@testing-library/react';
import React from 'react';
import { beforeEach, describe, expect, test, vi } from 'vitest';

const mockOidcAuthProvider = vi.fn(({ children }: React.PropsWithChildren) => <>{children}</>);

vi.mock('react-oidc-context', () => ({
  AuthProvider: (props: React.PropsWithChildren<Record<string, unknown>>) =>
    mockOidcAuthProvider(props),
}));

// `apps/common/frontend/src/utils/index.ts` is a barrel that re-exports
// voteRequests, which transitively depends on `@daml.js/splice-amulet`
// codegen. Mock the barrel to keep this test focused on AuthProvider props.
vi.mock('../../utils', async () => {
  const auth = await vi.importActual<typeof import('../../utils/auth')>('../../utils/auth');
  return { ...auth };
});

import AuthProvider from '../../components/AuthProvider';
import { Algorithm, AuthConfig } from '../../config/schema';

const rs256Config: AuthConfig = {
  algorithm: Algorithm.RS256,
  authority: 'https://idp.example.com/',
  client_id: 'test-client',
  token_audience: 'https://api.example.com',
  token_scope: 'wallet',
};

describe('AuthProvider', () => {
  beforeEach(() => {
    mockOidcAuthProvider.mockClear();
  });

  test('passes automaticSilentRenew=true to the OIDC provider', () => {
    render(
      <AuthProvider authConf={rs256Config}>
        <div />
      </AuthProvider>
    );
    expect(mockOidcAuthProvider).toHaveBeenCalled();
    const props = mockOidcAuthProvider.mock.calls[0][0] as Record<string, unknown>;
    expect(props.automaticSilentRenew).toBe(true);
  });

  test('hs256-unsafe config short-circuits without rendering the OIDC provider', () => {
    render(
      <AuthProvider
        authConf={{
          algorithm: Algorithm.HS256UNSAFE,
          secret: 'shh',
          token_audience: 'https://api.example.com',
        }}
      >
        <div />
      </AuthProvider>
    );
    expect(mockOidcAuthProvider).not.toHaveBeenCalled();
  });
});

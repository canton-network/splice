// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireAuthExpired } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, waitFor } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

const signinSilent = vi.fn();
const removeUser = vi.fn();

let mockUser: { access_token: string; refresh_token?: string } | undefined;

vi.mock('react-oidc-context', () => ({
  useAuth: () => ({
    isAuthenticated: true,
    user: mockUser,
    settings: { authority: 'https://idp.example.com', client_id: 'test' },
    signinSilent,
    removeUser,
    error: null,
  }),
}));

vi.mock('../../utils', async () => {
  const auth = await vi.importActual<typeof import('../../utils/auth')>('../../utils/auth');
  return { ...auth };
});

import { Algorithm, AuthConfig } from '../../config/schema';
import { UserProvider } from '../../contexts/UserContext';

const rs256Config: AuthConfig = {
  algorithm: Algorithm.RS256,
  authority: 'https://idp.example.com/',
  client_id: 'test-client',
  token_audience: 'https://api.example.com',
  token_scope: 'wallet',
};

const fakeJwt = (sub: string): string => {
  const b64u = (s: string) => btoa(s).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  const header = b64u(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const payload = b64u(JSON.stringify({ sub, exp: 9999999999, iat: 0 }));
  return `${header}.${payload}.fakesig`;
};

const Harness: React.FC = () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <UserProvider authConf={rs256Config}>
        <div data-testid="children" />
      </UserProvider>
    </QueryClientProvider>
  );
};

beforeEach(() => {
  signinSilent.mockReset();
  removeUser.mockReset();
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { ...window.location, origin: 'http://localhost:3000', href: 'http://localhost:3000/' },
  });
});

afterEach(() => {
  mockUser = undefined;
});

describe('renewOrSignout on splice:auth-expired', () => {
  test('calls signinSilent when a refresh_token is present', async () => {
    mockUser = { access_token: fakeJwt('alice'), refresh_token: 'rt-1' };
    signinSilent.mockResolvedValue(undefined);
    removeUser.mockResolvedValue(undefined);
    render(<Harness />);

    fireAuthExpired();

    await waitFor(() => expect(signinSilent).toHaveBeenCalledOnce());
    expect(removeUser).not.toHaveBeenCalled();
  });

  test('signs out when there is no refresh_token in the stored user', async () => {
    mockUser = { access_token: fakeJwt('alice') };
    removeUser.mockResolvedValue(undefined);
    render(<Harness />);

    fireAuthExpired();

    await waitFor(() => expect(removeUser).toHaveBeenCalledOnce());
    expect(signinSilent).not.toHaveBeenCalled();
  });

  test('signs out when signinSilent rejects (refresh token revoked or IdP unreachable)', async () => {
    mockUser = { access_token: fakeJwt('alice'), refresh_token: 'rt-1' };
    signinSilent.mockRejectedValue(new Error('refresh token revoked'));
    removeUser.mockResolvedValue(undefined);
    render(<Harness />);

    fireAuthExpired();

    await waitFor(() => expect(signinSilent).toHaveBeenCalledOnce());
    await waitFor(() => expect(removeUser).toHaveBeenCalledOnce());
  });

  test('single-flights: concurrent expired events trigger one signinSilent call', async () => {
    mockUser = { access_token: fakeJwt('alice'), refresh_token: 'rt-1' };
    let resolveSilent: ((v?: undefined) => void) | undefined;
    signinSilent.mockImplementation(
      () =>
        new Promise<void>(resolve => {
          resolveSilent = resolve;
        })
    );
    removeUser.mockResolvedValue(undefined);
    render(<Harness />);

    fireAuthExpired();
    fireAuthExpired();
    fireAuthExpired();
    await waitFor(() => expect(signinSilent).toHaveBeenCalledOnce());
    resolveSilent?.();
  });
});

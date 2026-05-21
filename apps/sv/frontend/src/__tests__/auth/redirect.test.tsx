// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Algorithm, AuthConfig, UserProvider } from '@lfdecentralizedtrust/splice-common-frontend';
import { render, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import React, { useEffect } from 'react';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

import { SvAdminClientProvider, useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { server, svUrl } from '../setup/setup';

const removeUser = vi.fn().mockResolvedValue(undefined);

vi.mock('react-oidc-context', () => ({
  useAuth: () => ({
    isAuthenticated: true,
    user: { access_token: makeFakeJwt('test-user') },
    settings: {
      authority: 'https://example.auth0.com',
      client_id: 'test-client',
    },
    removeUser,
    error: null,
  }),
  AuthProvider: ({ children }: React.PropsWithChildren) => <>{children}</>,
}));

let locationHrefSet: string | undefined;
beforeEach(() => {
  locationHrefSet = undefined;
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: {
      ...window.location,
      origin: 'http://localhost:3211',
      get href() {
        return locationHrefSet ?? 'http://localhost:3211/';
      },
      set href(v: string) {
        locationHrefSet = v;
      },
    },
  });
});

function makeFakeJwt(sub: string): string {
  const b64u = (s: string) => btoa(s).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  const header = b64u(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64u(
    JSON.stringify({ sub, aud: 'https://sv.example.com', iat: 0, exp: 9999999999 })
  );
  return `${header}.${payload}.fakesig`;
}

const rs256Config: AuthConfig = {
  algorithm: Algorithm.RS256,
  authority: 'https://example.auth0.com',
  client_id: 'test-client',
  token_audience: 'https://sv.example.com',
};

const Caller: React.FC = () => {
  const svClient = useSvAdminClient();
  useEffect(() => {
    svClient.isAuthorized().catch(() => {
      // Expected: the 401 throws here. The signout side-effect is what we test.
    });
  }, [svClient]);
  return null;
};

afterEach(() => {
  removeUser.mockClear();
});

const renderWithProviders = () =>
  render(
    <UserProvider authConf={rs256Config}>
      <SvAdminClientProvider url={svUrl}>
        <Caller />
      </SvAdminClientProvider>
    </UserProvider>
  );

describe('401 from SV API logs the user out', () => {
  test('on 401: clears the local user and navigates to origin', async () => {
    server.use(
      http.get(`${svUrl}/v0/admin/authorization`, () => new HttpResponse(null, { status: 401 }))
    );

    renderWithProviders();

    await waitFor(() => expect(removeUser).toHaveBeenCalled());
    await waitFor(() => expect(locationHrefSet).toBe('http://localhost:3211'));
  });

  test('on 200: does nothing', async () => {
    renderWithProviders();

    expect(removeUser).not.toHaveBeenCalled();
    expect(locationHrefSet).toBeUndefined();
  });
});

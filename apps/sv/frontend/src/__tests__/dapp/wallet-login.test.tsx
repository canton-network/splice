// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

import App from '../../App';
import { SvConfigProvider } from '../../utils';
import { buildScanMock } from '../mocks/handlers/scan-api';
import { server } from '../setup/setup';
import {
  dappScanUrl,
  disableDappModeConfig,
  enableDappModeConfig,
  mockVoteDelegationLedgerApi,
} from './dappConfig';

const mocks = vi.hoisted(() => {
  const wallet = {
    primary: true,
    partyId: 'delegated-voter::1220aa00bb11cc22dd33ee44ff55',
    status: 'allocated',
    hint: 'voter',
    publicKey: 'pk',
    namespace: 'ns',
    networkId: 'localnet',
    signingProviderId: 'sp',
  };
  const client = {
    cip103RpcUrl: 'http://localhost:3030/api/v0/dapp',
    init: vi.fn(),
    connect: vi.fn(),
    disconnect: vi.fn(),
    isConnected: vi.fn(),
    listAccounts: vi.fn(),
    onAccountsChanged: vi.fn(),
    removeOnAccountsChanged: vi.fn(),
    prepareExecuteAndWait: vi.fn(),
    ledgerApi: vi.fn(),
  };
  return { client, wallet };
});

vi.mock('../../dapp/dappSdkClient', () => ({
  getDappSdkClient: () => mocks.client,
}));

const AppWithConfig = () => (
  <SvConfigProvider>
    <App />
  </SvConfigProvider>
);

describe('dApp mode login', () => {
  beforeEach(() => {
    enableDappModeConfig();
    server.use(...buildScanMock(dappScanUrl));
    window.history.pushState({}, '', '/');
    mocks.client.init.mockResolvedValue(undefined);
    mocks.client.onAccountsChanged.mockResolvedValue(undefined);
    mocks.client.removeOnAccountsChanged.mockResolvedValue(undefined);
    mocks.client.disconnect.mockResolvedValue(undefined);
    mocks.client.isConnected.mockResolvedValue({ isConnected: false, isNetworkConnected: false });
    mocks.client.listAccounts.mockResolvedValue([]);
    mockVoteDelegationLedgerApi(mocks.client);
  });

  afterEach(() => {
    disableDappModeConfig();
    vi.clearAllMocks();
  });

  test('shows the wallet connect screen instead of the OIDC login', async () => {
    render(<AppWithConfig />);

    expect(await screen.findByTestId('connect-wallet-button')).toBeInTheDocument();
    expect(screen.queryByText('Log In')).toBeNull();
  });

  test('connecting a wallet enters the app', async () => {
    const user = userEvent.setup();
    mocks.client.connect.mockResolvedValue({ isConnected: true, isNetworkConnected: true });
    render(<AppWithConfig />);

    expect(await screen.findByTestId('connect-wallet-button')).toBeInTheDocument();
    mocks.client.listAccounts.mockResolvedValue([mocks.wallet]);
    await user.click(screen.getByTestId('connect-wallet-button'));

    expect(await screen.findByTestId('navlink-governance')).toBeInTheDocument();
  });

  test('a failed connection shows the wallet error', async () => {
    const user = userEvent.setup();
    mocks.client.connect.mockResolvedValue({
      isConnected: false,
      isNetworkConnected: false,
      reason: 'wallet gateway unreachable',
    });
    render(<AppWithConfig />);

    expect(await screen.findByTestId('connect-wallet-button')).toBeInTheDocument();
    await user.click(screen.getByTestId('connect-wallet-button'));

    const alert = await screen.findByTestId('wallet-login-error');
    expect(alert.textContent).toMatch(/wallet gateway unreachable/);
  });

  test('a restored session skips the login screen', async () => {
    mocks.client.isConnected.mockResolvedValue({ isConnected: true, isNetworkConnected: true });
    mocks.client.listAccounts.mockResolvedValue([mocks.wallet]);
    render(<AppWithConfig />);

    expect(await screen.findByTestId('navlink-governance')).toBeInTheDocument();
  });

  test('logout disconnects the wallet and returns to the connect screen', async () => {
    const user = userEvent.setup();
    mocks.client.isConnected.mockResolvedValue({ isConnected: true, isNetworkConnected: true });
    mocks.client.listAccounts.mockResolvedValue([mocks.wallet]);
    const { container } = render(<AppWithConfig />);

    expect(await screen.findByTestId('navlink-governance')).toBeInTheDocument();

    const logoutButton = container.querySelector('#logout-button');
    expect(logoutButton).not.toBeNull();
    await user.click(logoutButton as Element);

    await waitFor(() => expect(mocks.client.disconnect).toHaveBeenCalled());
    expect(await screen.findByTestId('connect-wallet-button')).toBeInTheDocument();
  });
});

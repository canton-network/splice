// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import type { AccountsChangedEvent, Wallet } from '@canton-network/dapp-sdk';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

import {
  WalletSessionProvider,
  selectPrimaryWallet,
  useWalletSession,
} from '../../dapp/WalletSessionContext';
import { SvConfigProvider } from '../../utils';
import {
  dappSvPartyId,
  dappVoteDelegationCid,
  disableDappModeConfig,
  enableDappModeConfig,
  mockVoteDelegationLedgerApi,
} from './dappConfig';

const mocks = vi.hoisted(() => {
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
  return { client };
});

vi.mock('../../dapp/dappSdkClient', () => ({
  getDappSdkClient: () => mocks.client,
}));

const testWallet = (partyId: string, primary = false): Wallet =>
  ({
    primary,
    partyId,
    status: 'allocated',
    hint: 'voter-hint',
    publicKey: 'public-key',
    namespace: 'namespace',
    networkId: 'localnet',
    signingProviderId: 'signing-provider',
  }) as Wallet;

const Probe: React.FC = () => {
  const session = useWalletSession();
  return (
    <div>
      <span data-testid="wallet-status">{session.status}</span>
      <span data-testid="wallet-party">{session.voterPartyId ?? ''}</span>
      <span data-testid="wallet-sv">{session.svPartyId ?? ''}</span>
      <span data-testid="wallet-delegation">{session.voteDelegationCid ?? ''}</span>
      <span data-testid="wallet-discovering">
        {session.isDiscoveringVoteDelegation ? 'yes' : 'no'}
      </span>
      <span data-testid="wallet-error">{session.errorMessage ?? ''}</span>
      <button onClick={() => void session.connect()}>connect</button>
      <button onClick={() => void session.disconnect()}>disconnect</button>
    </div>
  );
};

const renderSession = () =>
  render(
    <SvConfigProvider>
      <WalletSessionProvider>
        <Probe />
      </WalletSessionProvider>
    </SvConfigProvider>
  );

describe('WalletSessionProvider', () => {
  beforeEach(() => {
    enableDappModeConfig();
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

  test('bootstrap without a persisted session ends up disconnected', async () => {
    renderSession();
    await waitFor(() =>
      expect(screen.getByTestId('wallet-status').textContent).toBe('disconnected')
    );
    expect(screen.getByTestId('wallet-party').textContent).toBe('');
    // Regression: SDK >= 1.4 rejects event subscriptions without a connected
    // session ("Not connected — call connect() first"), so no subscription
    // may be attempted on a cold session.
    expect(mocks.client.onAccountsChanged).not.toHaveBeenCalled();
  });

  test('bootstrap restores a persisted session and selects the primary wallet', async () => {
    mocks.client.isConnected.mockResolvedValue({ isConnected: true, isNetworkConnected: true });
    mocks.client.listAccounts.mockResolvedValue([
      testWallet('other::party'),
      testWallet('primary::party', true),
    ]);
    mockVoteDelegationLedgerApi(mocks.client, { voterPartyId: 'primary::party' });
    renderSession();
    await waitFor(() => expect(screen.getByTestId('wallet-status').textContent).toBe('connected'));
    expect(screen.getByTestId('wallet-party').textContent).toBe('primary::party');
    await waitFor(() =>
      expect(screen.getByTestId('wallet-delegation').textContent).toBe(dappVoteDelegationCid)
    );
    expect(screen.getByTestId('wallet-sv').textContent).toBe(dappSvPartyId);
  });

  test('discovery failure surfaces a clear error without dropping the wallet session', async () => {
    mocks.client.isConnected.mockResolvedValue({ isConnected: true, isNetworkConnected: true });
    mocks.client.listAccounts.mockResolvedValue([testWallet('primary::party', true)]);
    mocks.client.ledgerApi.mockImplementation(async (params: { resource: string }) => {
      if (params.resource === '/v2/state/ledger-end') {
        return { offset: 1 };
      }
      return [];
    });
    renderSession();
    await waitFor(() => expect(screen.getByTestId('wallet-status').textContent).toBe('connected'));
    await waitFor(() =>
      expect(screen.getByTestId('wallet-error').textContent).toMatch(/No VoteDelegation/)
    );
    expect(screen.getByTestId('wallet-delegation').textContent).toBe('');
  });

  test('connect stores the wallet party on success', async () => {
    const user = userEvent.setup();
    mocks.client.connect.mockResolvedValue({ isConnected: true, isNetworkConnected: true });
    renderSession();
    await waitFor(() =>
      expect(screen.getByTestId('wallet-status').textContent).toBe('disconnected')
    );

    mocks.client.listAccounts.mockResolvedValue([testWallet('voter::party', true)]);
    mockVoteDelegationLedgerApi(mocks.client, { voterPartyId: 'voter::party' });
    await user.click(screen.getByText('connect'));

    await waitFor(() => expect(screen.getByTestId('wallet-status').textContent).toBe('connected'));
    expect(screen.getByTestId('wallet-party').textContent).toBe('voter::party');
  });

  test('connect failure surfaces the wallet reason', async () => {
    const user = userEvent.setup();
    mocks.client.connect.mockResolvedValue({
      isConnected: false,
      isNetworkConnected: false,
      reason: 'user closed the picker',
    });
    renderSession();
    await waitFor(() =>
      expect(screen.getByTestId('wallet-status').textContent).toBe('disconnected')
    );

    await user.click(screen.getByText('connect'));

    await waitFor(() =>
      expect(screen.getByTestId('wallet-status').textContent).toBe('wallet_connection_failed')
    );
    expect(screen.getByTestId('wallet-error').textContent).toBe('user closed the picker');
  });

  test('disconnect clears the session', async () => {
    const user = userEvent.setup();
    mocks.client.isConnected.mockResolvedValue({ isConnected: true, isNetworkConnected: true });
    mocks.client.listAccounts.mockResolvedValue([testWallet('voter::party', true)]);
    mockVoteDelegationLedgerApi(mocks.client, { voterPartyId: 'voter::party' });
    renderSession();
    await waitFor(() => expect(screen.getByTestId('wallet-status').textContent).toBe('connected'));

    await user.click(screen.getByText('disconnect'));

    await waitFor(() =>
      expect(screen.getByTestId('wallet-status').textContent).toBe('disconnected')
    );
    expect(screen.getByTestId('wallet-party').textContent).toBe('');
    expect(screen.getByTestId('wallet-delegation').textContent).toBe('');
    expect(mocks.client.disconnect).toHaveBeenCalled();
  });

  test('accountsChanged events update the session once connected', async () => {
    mocks.client.isConnected.mockResolvedValue({ isConnected: true, isNetworkConnected: true });
    mocks.client.listAccounts.mockResolvedValue([testWallet('initial::party', true)]);
    mockVoteDelegationLedgerApi(mocks.client, { voterPartyId: 'initial::party' });
    renderSession();
    await waitFor(() => expect(screen.getByTestId('wallet-status').textContent).toBe('connected'));
    expect(mocks.client.onAccountsChanged).toHaveBeenCalledTimes(1);
    const listener = mocks.client.onAccountsChanged.mock.calls[0][0] as (
      accounts: AccountsChangedEvent
    ) => void;

    mockVoteDelegationLedgerApi(mocks.client, { voterPartyId: 'rotated::party' });
    act(() => listener([testWallet('rotated::party', true)]));
    await waitFor(() =>
      expect(screen.getByTestId('wallet-party').textContent).toBe('rotated::party')
    );

    act(() => listener([]));
    await waitFor(() =>
      expect(screen.getByTestId('wallet-status').textContent).toBe('disconnected')
    );
  });

  test('connecting subscribes to account changes', async () => {
    const user = userEvent.setup();
    mocks.client.connect.mockResolvedValue({ isConnected: true, isNetworkConnected: true });
    renderSession();
    await waitFor(() =>
      expect(screen.getByTestId('wallet-status').textContent).toBe('disconnected')
    );
    expect(mocks.client.onAccountsChanged).not.toHaveBeenCalled();

    mocks.client.listAccounts.mockResolvedValue([testWallet('voter::party', true)]);
    mockVoteDelegationLedgerApi(mocks.client, { voterPartyId: 'voter::party' });
    await user.click(screen.getByText('connect'));

    await waitFor(() => expect(screen.getByTestId('wallet-status').textContent).toBe('connected'));
    expect(mocks.client.onAccountsChanged).toHaveBeenCalledTimes(1);
  });
});

describe('selectPrimaryWallet', () => {
  test('prefers the primary account', () => {
    const accounts = [testWallet('first::party'), testWallet('primary::party', true)];
    expect(selectPrimaryWallet(accounts)?.partyId).toBe('primary::party');
  });

  test('falls back to the first account', () => {
    const accounts = [testWallet('first::party'), testWallet('second::party')];
    expect(selectPrimaryWallet(accounts)?.partyId).toBe('first::party');
  });

  test('returns undefined for no accounts', () => {
    expect(selectPrimaryWallet([])).toBeUndefined();
  });
});

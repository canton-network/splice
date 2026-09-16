// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DsoInfo } from '@canton-network/splice-common-frontend';
import { UseQueryResult } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

import { useDsoInfos } from '../../contexts/SvContext';
import { Governance } from '../../routes/governance';
import { buildScanMock } from '../mocks/handlers/scan-api';
import { server, svUrl } from '../setup/setup';
import {
  dappScanUrl,
  dappSvPartyId,
  disableDappModeConfig,
  enableDappModeConfig,
  mockVoteDelegationLedgerApi,
} from './dappConfig';
import { DappWrapper } from './helpers';

const mocks = vi.hoisted(() => {
  const client = {
    cip103RpcUrl: 'http://localhost:3030/api/v0/dapp',
    init: vi.fn(async () => undefined),
    connect: vi.fn(),
    disconnect: vi.fn(async () => undefined),
    isConnected: vi.fn(async () => ({ isConnected: true, isNetworkConnected: true })),
    listAccounts: vi.fn(async () => [
      {
        primary: true,
        partyId: 'delegated-voter::1220aa00bb11cc22dd33ee44ff55',
        status: 'allocated',
        hint: 'voter',
        publicKey: 'pk',
        namespace: 'ns',
        networkId: 'localnet',
        signingProviderId: 'sp',
      },
    ]),
    onAccountsChanged: vi.fn(async () => undefined),
    removeOnAccountsChanged: vi.fn(async () => undefined),
    prepareExecuteAndWait: vi.fn(),
    ledgerApi: vi.fn(),
  };
  return { client };
});

vi.mock('../../dapp/dappSdkClient', () => ({
  getDappSdkClient: () => mocks.client,
}));

// Any request to the SV backend fails loudly: in dApp mode every read must be
// served by Scan.
const svBackendRejections = [http.all(`${svUrl}/*`, () => new HttpResponse(null, { status: 500 }))];

describe('dApp mode reads from Scan', () => {
  beforeEach(() => {
    enableDappModeConfig();
    server.use(...buildScanMock(dappScanUrl), ...svBackendRejections);
    mockVoteDelegationLedgerApi(mocks.client);
  });

  afterEach(() => {
    disableDappModeConfig();
    vi.clearAllMocks();
  });

  test('governance page renders vote requests from Scan', async () => {
    render(
      <DappWrapper>
        <Governance />
      </DappWrapper>
    );

    expect(await screen.findByTestId('governance-page-header')).toBeInTheDocument();

    // None of the fixture vote requests carry a vote by the configured SV
    // party, so all of them require action.
    await waitFor(() => {
      expect(screen.getAllByTestId('action-required-card').length).toBe(4);
    });
  });

  test('governance page renders vote history from Scan', async () => {
    render(
      <DappWrapper>
        <Governance />
      </DappWrapper>
    );

    expect(await screen.findByTestId('vote-history-section')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getAllByTestId('vote-history-row').length).toBeGreaterThan(0);
    });
  });

  test('useDsoInfos reports the ACS-discovered delegating SV party', async () => {
    const Probe: React.FC = () => {
      const dsoInfos: UseQueryResult<DsoInfo> = useDsoInfos();
      return <span data-testid="sv-party">{dsoInfos.data?.svPartyId ?? 'pending'}</span>;
    };

    render(
      <DappWrapper>
        <Probe />
      </DappWrapper>
    );

    await waitFor(() => {
      expect(screen.getByTestId('sv-party').textContent).toBe(dappSvPartyId);
    });
  });

  test('useDsoInfos overrides the Scan sv_party_id with the discovered party', async () => {
    mockVoteDelegationLedgerApi(mocks.client, {
      svPartyId: 'Delegating-SV::1220ffeeddccbbaa',
    });

    const Probe: React.FC = () => {
      const dsoInfos: UseQueryResult<DsoInfo> = useDsoInfos();
      return <span data-testid="sv-party">{dsoInfos.data?.svPartyId ?? 'pending'}</span>;
    };

    render(
      <DappWrapper>
        <Probe />
      </DappWrapper>
    );

    await waitFor(() => {
      expect(screen.getByTestId('sv-party').textContent).toBe('Delegating-SV::1220ffeeddccbbaa');
    });
  });
});

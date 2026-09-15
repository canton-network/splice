// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ErrorCode } from '@canton-network/dapp-sdk';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';

import { ProposalVoteForm } from '../../components/governance/ProposalVoteForm';
import { buildScanMock } from '../mocks/handlers/scan-api';
import { voteRequests } from '../mocks/constants';
import { server } from '../setup/setup';
import {
  dappScanUrl,
  dappSvPartyId,
  dappVoteDelegationCid,
  dappVoterPartyId,
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
    prepareExecuteAndWait: vi.fn(async () => ({ tx: { payload: { updateId: 'update-1' } } })),
    ledgerApi: vi.fn(),
  };
  return { client };
});

vi.mock('../../dapp/dappSdkClient', () => ({
  getDappSdkClient: () => mocks.client,
}));

const voteRequestContractId = voteRequests.dso_rules_vote_requests[0]
  .contract_id as ContractId<VoteRequest>;

const renderForm = () =>
  render(
    <DappWrapper>
      <ProposalVoteForm
        voteRequestContractId={voteRequestContractId}
        currentSvPartyId={dappSvPartyId}
        votes={[]}
      />
    </DappWrapper>
  );

describe('dApp mode vote casting', () => {
  beforeEach(() => {
    enableDappModeConfig();
    server.use(...buildScanMock(dappScanUrl));
    mockVoteDelegationLedgerApi(mocks.client);
  });

  afterEach(() => {
    disableDappModeConfig();
    vi.clearAllMocks();
  });

  test('accepting a proposal signs through the wallet gateway', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.click(await screen.findByTestId('your-vote-accept'));

    expect(await screen.findByTestId('vote-submission-success')).toBeInTheDocument();
    expect(mocks.client.prepareExecuteAndWait).toHaveBeenCalledTimes(1);

    const params = (mocks.client.prepareExecuteAndWait.mock.calls[0] as unknown[])[0] as {
      actAs: string[];
      commands: { ExerciseCommand: { contractId: string; choice: string } }[];
    };
    expect(params.actAs).toEqual([dappVoterPartyId]);
    expect(params.commands[0].ExerciseCommand.contractId).toBe(dappVoteDelegationCid);
    expect(params.commands[0].ExerciseCommand.choice).toBe('VoteDelegation_CastVote');
  });

  test('wallet rejection surfaces the existing error alert', async () => {
    mocks.client.prepareExecuteAndWait.mockRejectedValue(
      Object.assign(new Error('user cancelled in the wallet'), { code: ErrorCode.UserCancelled })
    );
    const user = userEvent.setup();
    renderForm();

    await user.click(await screen.findByTestId('your-vote-reject'));

    expect(await screen.findByTestId('vote-submission-error')).toBeInTheDocument();
  });
});

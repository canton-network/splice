// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ErrorCode } from '@canton-network/dapp-sdk';
import * as scanOpenapi from '@canton-network/scan-openapi';
import { afterEach, describe, expect, test, vi } from 'vitest';

import { RelTime } from '@daml.js/daml-stdlib-DA-Time-Types-1.0.0/lib/DA/Time/Types/module';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { DappSdkClient } from '../../dapp/dappSdkClient';
import {
  createVoteDelegationSubmission,
  SignatureRejectedError,
  VoteDelegationContextError,
} from '../../dapp/voteDelegationSubmission';
const VOTER_PARTY = 'voter::1220bb';
const SV_PARTY = 'Delegating-SV::1220aa';

const DELEGATION_CID = '00votedelegation';

const dsoInfoResponse = {
  sv_party_id: 'Scan-Sponsor-SV::1220cc',
  dso_rules: {
    contract: {
      contract_id: '00dsorules',
      template_id: 'pkg:Splice.DsoRules:DsoRules',
      created_event_blob: 'dso-rules-blob',
      payload: {},
    },
  },
};

const currentVoteRequest = {
  contract_id: '00currentvoterequest',
  template_id: 'pkg:Splice.DsoRules:VoteRequest',
  created_event_blob: 'vote-request-blob',
  payload: { trackingCid: '00trackingcid' },
};

interface FakeClients {
  scanClient: scanOpenapi.ScanApi;
  sdkClient: DappSdkClient;
  prepareExecuteAndWait: ReturnType<typeof vi.fn>;
  lookupDsoRulesVoteRequest: ReturnType<typeof vi.fn>;
  listDsoRulesVoteRequests: ReturnType<typeof vi.fn>;
}

const buildFakes = (): FakeClients => {
  const prepareExecuteAndWait = vi.fn(async () => ({
    tx: { payload: { updateId: 'update-1' } },
  }));
  const lookupDsoRulesVoteRequest = vi.fn(async () => ({
    dso_rules_vote_request: currentVoteRequest,
  }));
  const listDsoRulesVoteRequests = vi.fn(async () => ({
    dso_rules_vote_requests: [currentVoteRequest],
  }));
  const scanClient = {
    getDsoInfo: vi.fn(async () => dsoInfoResponse),
    lookupDsoRulesVoteRequest,
    listDsoRulesVoteRequests,
  } as unknown as scanOpenapi.ScanApi;
  const sdkClient = { prepareExecuteAndWait } as unknown as DappSdkClient;
  return {
    scanClient,
    sdkClient,
    prepareExecuteAndWait,
    lookupDsoRulesVoteRequest,
    listDsoRulesVoteRequests,
  };
};

const buildSubmission = (
  fakes: FakeClients,
  args: {
    voterPartyId?: string | undefined;
    svPartyId?: string | undefined;
    voteDelegationCid?: string | undefined;
  } = {}
) =>
  createVoteDelegationSubmission({
    scanClient: fakes.scanClient,
    sdkClient: fakes.sdkClient,
    getVoterPartyId: () => ('voterPartyId' in args ? args.voterPartyId : VOTER_PARTY),
    getSvPartyId: () => ('svPartyId' in args ? args.svPartyId : SV_PARTY),
    getVoteDelegationCid: () =>
      'voteDelegationCid' in args ? args.voteDelegationCid : DELEGATION_CID,
  });

const castArgs = {
  voteRequestContractId: '00currentvoterequest',
  isAccepted: true,
  reasonUrl: '',
  reasonDescription: 'agreed',
};

afterEach(() => {
  vi.clearAllMocks();
});

describe('submitCastVote', () => {
  test('submits VoteDelegation_CastVote with disclosures through the gateway', async () => {
    const fakes = buildFakes();
    await buildSubmission(fakes).submitCastVote(castArgs);

    expect(fakes.prepareExecuteAndWait).toHaveBeenCalledTimes(1);
    const params = fakes.prepareExecuteAndWait.mock.calls[0][0];
    expect(params.actAs).toEqual([VOTER_PARTY]);
    const exercise = params.commands[0].ExerciseCommand;
    expect(exercise.templateId).toBe(
      '#splice-dso-governance:Splice.DsoRules.VoteDelegation:VoteDelegation'
    );
    expect(exercise.contractId).toBe('00votedelegation');
    expect(exercise.choiceArgument.castVote.requestCid).toBe('00currentvoterequest');
    expect(exercise.choiceArgument.castVote.vote.sv).toBe(SV_PARTY);
    expect(exercise.choiceArgument.castVote.voterParty).toBe(VOTER_PARTY);
    expect(params.disclosedContracts).toEqual([
      {
        contractId: '00dsorules',
        createdEventBlob: 'dso-rules-blob',
        templateId: 'pkg:Splice.DsoRules:DsoRules',
      },
      {
        contractId: '00currentvoterequest',
        createdEventBlob: 'vote-request-blob',
        templateId: 'pkg:Splice.DsoRules:VoteRequest',
      },
    ]);
  });

  test('re-resolves the tracking cid to the current VoteRequest contract', async () => {
    const fakes = buildFakes();

    await buildSubmission(fakes).submitCastVote({
      ...castArgs,
      // The UI holds the tracking cid; the current contract id differs.
      voteRequestContractId: '00trackingcid',
    });

    expect(fakes.lookupDsoRulesVoteRequest).toHaveBeenCalledWith('00trackingcid');
    const params = fakes.prepareExecuteAndWait.mock.calls[0][0];
    expect(params.commands[0].ExerciseCommand.choiceArgument.castVote.requestCid).toBe(
      '00currentvoterequest'
    );
  });

  test('fails clearly when the vote request cannot be resolved', async () => {
    const fakes = buildFakes();
    fakes.lookupDsoRulesVoteRequest.mockRejectedValue(
      Object.assign(new Error('VoteRequest contract not found.'), { code: 404 })
    );

    await expect(buildSubmission(fakes).submitCastVote(castArgs)).rejects.toBeInstanceOf(
      VoteDelegationContextError
    );
    expect(fakes.listDsoRulesVoteRequests).not.toHaveBeenCalled();
    expect(fakes.prepareExecuteAndWait).not.toHaveBeenCalled();
  });

  test('surfaces Scan outages from lookup instead of falling through to list', async () => {
    const fakes = buildFakes();
    const scanOutage = Object.assign(new Error('Unknown API Status Code!'), { code: 500 });
    fakes.lookupDsoRulesVoteRequest.mockRejectedValue(scanOutage);

    await expect(buildSubmission(fakes).submitCastVote(castArgs)).rejects.toBe(scanOutage);
    expect(fakes.listDsoRulesVoteRequests).not.toHaveBeenCalled();
    expect(fakes.prepareExecuteAndWait).not.toHaveBeenCalled();
  });

  test('maps wallet cancellation to SignatureRejectedError', async () => {
    const fakes = buildFakes();
    fakes.prepareExecuteAndWait.mockRejectedValue(
      Object.assign(new Error('user dismissed the request'), { code: ErrorCode.UserCancelled })
    );

    await expect(buildSubmission(fakes).submitCastVote(castArgs)).rejects.toBeInstanceOf(
      SignatureRejectedError
    );
  });

  test('requires a connected wallet', async () => {
    const fakes = buildFakes();
    await expect(
      buildSubmission(fakes, { voterPartyId: undefined }).submitCastVote(castArgs)
    ).rejects.toBeInstanceOf(VoteDelegationContextError);
  });

  test('requires a discovered VoteDelegation contract id', async () => {
    const fakes = buildFakes();
    await expect(
      buildSubmission(fakes, { voteDelegationCid: undefined }).submitCastVote(castArgs)
    ).rejects.toThrow(/No VoteDelegation discovered/);
  });
});

describe('submitCreateVoteRequest', () => {
  const action: ActionRequiringConfirmation = {
    tag: 'ARC_DsoRules',
    value: {
      dsoAction: {
        tag: 'SRARC_UpdateSvRewardWeight',
        value: { svParty: SV_PARTY, newRewardWeight: '1000' },
      },
    },
  };

  const requestArgs = {
    requester: SV_PARTY,
    action,
    url: 'https://example.com/proposal',
    description: 'Update reward weight',
    expiration: { microseconds: '604800000000' } as RelTime,
  };

  test('submits VoteDelegation_RequestVote through the gateway', async () => {
    const fakes = buildFakes();
    await buildSubmission(fakes).submitCreateVoteRequest(requestArgs);

    const params = fakes.prepareExecuteAndWait.mock.calls[0][0];
    expect(params.actAs).toEqual([VOTER_PARTY]);
    const exercise = params.commands[0].ExerciseCommand;
    expect(exercise.choice).toBe('VoteDelegation_RequestVote');
    expect(exercise.choiceArgument.requestVote.requester).toBe(SV_PARTY);
    expect(exercise.choiceArgument.requestVote.voterParty).toBe(VOTER_PARTY);
    expect(exercise.choiceArgument.requestVote.voteRequestTimeout).toEqual({
      microseconds: '604800000000',
    });
    expect(exercise.choiceArgument.requestVote.targetEffectiveAt).toBeNull();
    expect(exercise.choiceArgument.requestVote.action).toEqual(
      ActionRequiringConfirmation.encode(action)
    );
    expect(params.disclosedContracts).toEqual([
      {
        contractId: '00dsorules',
        createdEventBlob: 'dso-rules-blob',
        templateId: 'pkg:Splice.DsoRules:DsoRules',
      },
    ]);
  });

  test('passes an effective time as ISO timestamp', async () => {
    const fakes = buildFakes();
    await buildSubmission(fakes).submitCreateVoteRequest({
      ...requestArgs,
      effectiveTime: new Date('2026-08-01T12:00:00.000Z'),
    });

    const params = fakes.prepareExecuteAndWait.mock.calls[0][0];
    expect(params.commands[0].ExerciseCommand.choiceArgument.requestVote.targetEffectiveAt).toBe(
      '2026-08-01T12:00:00.000Z'
    );
  });

  test('does not map on-ledger rejection messages to SignatureRejectedError', async () => {
    const fakes = buildFakes();
    const ledgerError = new Error('the transaction was rejected by the mediator');
    fakes.prepareExecuteAndWait.mockRejectedValue(ledgerError);

    await expect(buildSubmission(fakes).submitCreateVoteRequest(requestArgs)).rejects.toBe(
      ledgerError
    );
  });

  test('requires a discovered delegating SV party', async () => {
    const fakes = buildFakes();
    await expect(
      buildSubmission(fakes, { svPartyId: undefined }).submitCreateVoteRequest(requestArgs)
    ).rejects.toThrow(/delegating SV party/);
  });
});

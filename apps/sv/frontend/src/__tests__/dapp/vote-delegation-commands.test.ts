// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, test } from 'vitest';

import {
  buildVoteDelegationCastParams,
  buildVoteDelegationRequestParams,
  getVoteDelegationTemplateId,
} from '../../dapp/voteDelegationCommands';

const castArgs = {
  voteRequestContractId: '00currentvoterequest',
  accepted: true,
  reasonUrl: 'https://example.com/reason',
  reasonDescription: 'Looks good to me',
  voteDelegationCid: '00votedelegation',
  dsoRulesCid: '00dsorules',
  svPartyId: 'Delegating-SV::1220aa',
  voterPartyId: 'voter::1220bb',
};

describe('getVoteDelegationTemplateId', () => {
  test('builds the splice-dso-governance template id', () => {
    expect(getVoteDelegationTemplateId()).toBe(
      '#splice-dso-governance:Splice.DsoRules.VoteDelegation:VoteDelegation'
    );
  });
});

describe('buildVoteDelegationCastParams', () => {
  test('exercises VoteDelegation_CastVote as the voter party', () => {
    const params = buildVoteDelegationCastParams(castArgs);

    expect(params.actAs).toEqual(['voter::1220bb']);
    expect(params.commands).toHaveLength(1);
    const exercise = (params.commands[0] as { ExerciseCommand: Record<string, unknown> })
      .ExerciseCommand;
    expect(exercise.templateId).toBe(getVoteDelegationTemplateId());
    expect(exercise.contractId).toBe('00votedelegation');
    expect(exercise.choice).toBe('VoteDelegation_CastVote');
  });

  test('relays DsoRules_CastVote with the delegating SV vote and the voter party', () => {
    const params = buildVoteDelegationCastParams(castArgs);
    const exercise = (params.commands[0] as { ExerciseCommand: Record<string, unknown> })
      .ExerciseCommand;
    expect(exercise.choiceArgument).toEqual({
      dsoRulesCid: '00dsorules',
      castVote: {
        requestCid: '00currentvoterequest',
        vote: {
          sv: 'Delegating-SV::1220aa',
          accept: true,
          reason: {
            url: 'https://example.com/reason',
            body: 'Looks good to me',
          },
          optCastAt: null,
        },
        voterParty: 'voter::1220bb',
      },
    });
  });

  test('maps disclosed contracts into SDK shape', () => {
    const params = buildVoteDelegationCastParams({
      ...castArgs,
      disclosedContracts: [
        { contractId: '00dsorules', createdEventBlob: 'blob-a', templateId: 'pkg:M:DsoRules' },
        { contractId: '00currentvoterequest', createdEventBlob: 'blob-b' },
      ],
    });

    expect(params.disclosedContracts).toEqual([
      { contractId: '00dsorules', createdEventBlob: 'blob-a', templateId: 'pkg:M:DsoRules' },
      { contractId: '00currentvoterequest', createdEventBlob: 'blob-b' },
    ]);
  });

  test('omits disclosed contracts when none provided', () => {
    const params = buildVoteDelegationCastParams(castArgs);
    expect(params.disclosedContracts).toBeUndefined();
  });
});

describe('buildVoteDelegationRequestParams', () => {
  const requestArgs = {
    action: { tag: 'ARC_DsoRules', value: {} },
    reasonUrl: '',
    reasonDescription: 'A new proposal',
    voteRequestTimeoutMicroseconds: '604800000000',
    voteDelegationCid: '00votedelegation',
    dsoRulesCid: '00dsorules',
    svPartyId: 'Delegating-SV::1220aa',
    voterPartyId: 'voter::1220bb',
  };

  test('relays DsoRules_RequestVote with the delegating SV as requester', () => {
    const params = buildVoteDelegationRequestParams(requestArgs);

    expect(params.actAs).toEqual(['voter::1220bb']);
    const exercise = (params.commands[0] as { ExerciseCommand: Record<string, unknown> })
      .ExerciseCommand;
    expect(exercise.choice).toBe('VoteDelegation_RequestVote');
    expect(exercise.choiceArgument).toEqual({
      dsoRulesCid: '00dsorules',
      requestVote: {
        requester: 'Delegating-SV::1220aa',
        action: { tag: 'ARC_DsoRules', value: {} },
        reason: { url: '', body: 'A new proposal' },
        voteRequestTimeout: { microseconds: '604800000000' },
        targetEffectiveAt: null,
        voterParty: 'voter::1220bb',
      },
    });
  });

  test('passes an explicit effective time through', () => {
    const params = buildVoteDelegationRequestParams({
      ...requestArgs,
      targetEffectiveAt: '2026-08-01T00:00:00.000Z',
    });
    const exercise = (params.commands[0] as { ExerciseCommand: Record<string, unknown> })
      .ExerciseCommand;
    expect(
      (exercise.choiceArgument as { requestVote: { targetEffectiveAt: string } }).requestVote
        .targetEffectiveAt
    ).toBe('2026-08-01T00:00:00.000Z');
  });
});

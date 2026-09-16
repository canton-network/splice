// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import type { PrepareExecuteParams } from '@canton-network/dapp-sdk';

const DSO_GOVERNANCE_PACKAGE_NAME = 'splice-dso-governance';
const VOTE_DELEGATION_MODULE = 'Splice.DsoRules.VoteDelegation';
const VOTE_DELEGATION_ENTITY = 'VoteDelegation';
const CAST_VOTE_CHOICE = 'VoteDelegation_CastVote';
const REQUEST_VOTE_CHOICE = 'VoteDelegation_RequestVote';

export interface DisclosedContractInput {
  readonly contractId: string;
  readonly createdEventBlob: string;
  readonly templateId?: string;
}

export interface CastVoteCommandArgs {
  /** Current (re-resolved) VoteRequest contract id. */
  readonly voteRequestContractId: string;
  readonly accepted: boolean;
  readonly reasonUrl: string;
  readonly reasonDescription: string;
  readonly voteDelegationCid: string;
  readonly dsoRulesCid: string;
  /** The delegating SV: recorded as Vote.sv. */
  readonly svPartyId: string;
  /** The wallet party signing through the gateway (VoteDelegation.voterParty). */
  readonly voterPartyId: string;
  readonly disclosedContracts?: readonly DisclosedContractInput[];
}

export interface RequestVoteCommandArgs {
  /** Daml-LF JSON encoding of ActionRequiringConfirmation. */
  readonly action: unknown;
  readonly reasonUrl: string;
  readonly reasonDescription: string;
  /** RelTime microseconds for DsoRules_RequestVote.voteRequestTimeout. */
  readonly voteRequestTimeoutMicroseconds: string;
  /** ISO timestamp for targetEffectiveAt, or undefined for threshold-based. */
  readonly targetEffectiveAt?: string;
  readonly voteDelegationCid: string;
  readonly dsoRulesCid: string;
  readonly svPartyId: string;
  readonly voterPartyId: string;
  readonly disclosedContracts?: readonly DisclosedContractInput[];
}

export function getVoteDelegationTemplateId(): string {
  return `#${DSO_GOVERNANCE_PACKAGE_NAME}:${VOTE_DELEGATION_MODULE}:${VOTE_DELEGATION_ENTITY}`;
}

function toSdkDisclosedContracts(
  disclosed: readonly DisclosedContractInput[] | undefined
): PrepareExecuteParams['disclosedContracts'] {
  if (disclosed === undefined || disclosed.length === 0) {
    return undefined;
  }
  return disclosed.map(contract => ({
    contractId: contract.contractId,
    createdEventBlob: contract.createdEventBlob,
    ...(contract.templateId !== undefined ? { templateId: contract.templateId } : {}),
  }));
}

/**
 * CIP-103 params for a delegated cast: `VoteDelegation_CastVote` relaying
 * `DsoRules_CastVote` with `vote.sv` = delegating SV and `voterParty` set so
 * the delegated cast is co-authorized and recorded on-ledger.
 */
export function buildVoteDelegationCastParams(args: CastVoteCommandArgs): PrepareExecuteParams {
  const disclosedContracts = toSdkDisclosedContracts(args.disclosedContracts);

  return {
    commands: [
      {
        ExerciseCommand: {
          templateId: getVoteDelegationTemplateId(),
          contractId: args.voteDelegationCid,
          choice: CAST_VOTE_CHOICE,
          choiceArgument: {
            dsoRulesCid: args.dsoRulesCid,
            castVote: {
              requestCid: args.voteRequestContractId,
              vote: {
                sv: args.svPartyId,
                accept: args.accepted,
                reason: {
                  url: args.reasonUrl,
                  body: args.reasonDescription,
                },
                optCastAt: null,
              },
              voterParty: args.voterPartyId,
            },
          },
        },
      },
    ],
    actAs: [args.voterPartyId],
    ...(disclosedContracts !== undefined ? { disclosedContracts } : {}),
  };
}

/**
 * CIP-103 params for delegated proposal creation: `VoteDelegation_RequestVote`
 * relaying `DsoRules_RequestVote` with `requester` = delegating SV.
 */
export function buildVoteDelegationRequestParams(
  args: RequestVoteCommandArgs
): PrepareExecuteParams {
  const disclosedContracts = toSdkDisclosedContracts(args.disclosedContracts);

  return {
    commands: [
      {
        ExerciseCommand: {
          templateId: getVoteDelegationTemplateId(),
          contractId: args.voteDelegationCid,
          choice: REQUEST_VOTE_CHOICE,
          choiceArgument: {
            dsoRulesCid: args.dsoRulesCid,
            requestVote: {
              requester: args.svPartyId,
              action: args.action,
              reason: {
                url: args.reasonUrl,
                body: args.reasonDescription,
              },
              voteRequestTimeout: {
                microseconds: args.voteRequestTimeoutMicroseconds,
              },
              targetEffectiveAt: args.targetEffectiveAt ?? null,
              voterParty: args.voterPartyId,
            },
          },
        },
      },
    ],
    actAs: [args.voterPartyId],
    ...(disclosedContracts !== undefined ? { disclosedContracts } : {}),
  };
}

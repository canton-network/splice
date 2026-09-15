// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as scanOpenapi from '@canton-network/scan-openapi';

import { RelTime } from '@daml.js/daml-stdlib-DA-Time-Types-1.0.0/lib/DA/Time/Types/module';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { DappSdkClient } from './dappSdkClient';
import {
  buildVoteDelegationCastParams,
  buildVoteDelegationRequestParams,
  DisclosedContractInput,
} from './voteDelegationCommands';

/** Raised when dApp-mode configuration or wallet state cannot support a submission. */
export class VoteDelegationContextError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'VoteDelegationContextError';
  }
}

/** Raised when the user cancels or the wallet rejects the signature. */
export class SignatureRejectedError extends Error {
  readonly code = 'signature_rejected' as const;

  constructor(message = 'Signature rejected or cancelled in the wallet') {
    super(message);
    this.name = 'SignatureRejectedError';
  }
}

async function isUserCancelled(error: unknown): Promise<boolean> {
  if (error instanceof SignatureRejectedError) {
    return true;
  }
  if (typeof error === 'object' && error !== null && 'code' in error) {
    // Dynamic import keeps the SDK out of standard mode.
    const { ErrorCode } = await import('@canton-network/dapp-sdk');
    return (error as { code: unknown }).code === ErrorCode.UserCancelled;
  }
  return false;
}

const mapWalletError = async (error: unknown): Promise<Error> => {
  if (await isUserCancelled(error)) {
    return new SignatureRejectedError(
      error instanceof Error ? error.message : 'Signature rejected or cancelled in the wallet'
    );
  }
  return error instanceof Error ? error : new Error(String(error));
};

/** Scan OpenAPI clients throw ApiException with numeric HTTP `code` on 404. */
function isScanNotFound(error: unknown): boolean {
  if (typeof error !== 'object' || error === null || !('code' in error)) {
    return false;
  }
  return (error as { code: unknown }).code === 404;
}

interface ScanContract {
  contract_id: string;
  template_id?: string;
  created_event_blob?: string;
  payload: unknown;
}

const toDisclosure = (contract: ScanContract): DisclosedContractInput => {
  const blob = contract.created_event_blob;
  if (!blob) {
    throw new VoteDelegationContextError(
      `Scan returned ${contract.contract_id} without a created event blob — cannot disclose it.`
    );
  }
  return {
    contractId: contract.contract_id,
    createdEventBlob: blob,
    ...(contract.template_id !== undefined ? { templateId: contract.template_id } : {}),
  };
};

interface VoteDelegationContext {
  readonly voterPartyId: string;
  readonly svPartyId: string;
  readonly voteDelegationCid: string;
  readonly dsoRulesCid: string;
  /**
   * The voter's participant does not host DSO contracts, so submissions must
   * disclose DsoRules explicitly; without it, prepare fails with
   * PERMISSION_DENIED ("A security-sensitive error has been received").
   */
  readonly dsoRulesDisclosed: DisclosedContractInput;
}

export interface VoteDelegationSubmissionDeps {
  scanClient: scanOpenapi.ScanApi;
  sdkClient: DappSdkClient;
  getVoterPartyId: () => string | undefined;
  /** Delegating SV party from ACS-discovered VoteDelegation. */
  getSvPartyId: () => string | undefined;
  /** VoteDelegation contract id from ACS discovery. */
  getVoteDelegationCid: () => string | undefined;
}

export interface VoteDelegationSubmission {
  submitCastVote: (args: {
    voteRequestContractId: string;
    isAccepted: boolean;
    reasonUrl: string;
    reasonDescription: string;
  }) => Promise<void>;
  submitCreateVoteRequest: (args: {
    requester: string;
    action: ActionRequiringConfirmation;
    url: string;
    description: string;
    expiration: RelTime;
    effectiveTime?: Date;
  }) => Promise<void>;
}

/**
 * Governance submissions through the CIP-103 dApp API, exercising the
 * VoteDelegation contract as the connected wallet party.
 */
export function createVoteDelegationSubmission(
  deps: VoteDelegationSubmissionDeps
): VoteDelegationSubmission {
  const { scanClient, sdkClient, getVoterPartyId, getSvPartyId, getVoteDelegationCid } = deps;

  const resolveContext = async (operation: string): Promise<VoteDelegationContext> => {
    const voterPartyId = getVoterPartyId();
    if (!voterPartyId) {
      throw new VoteDelegationContextError(
        `Connect a wallet so the VoteDelegation voter party can sign the ${operation}.`
      );
    }
    const voteDelegationCid = getVoteDelegationCid();
    if (!voteDelegationCid) {
      throw new VoteDelegationContextError(
        'No VoteDelegation discovered for the connected wallet party — reconnect or ask the SV to create one.'
      );
    }
    const svPartyId = getSvPartyId();
    if (!svPartyId) {
      throw new VoteDelegationContextError(
        'VoteDelegation discovery did not yield a delegating SV party — reconnect and retry.'
      );
    }

    const dsoInfo = await scanClient.getDsoInfo();
    const dsoRulesContract = dsoInfo.dso_rules.contract as ScanContract;
    const dsoRulesCid = dsoRulesContract.contract_id;
    if (!dsoRulesCid) {
      throw new VoteDelegationContextError(
        'Scan /v0/dso did not return a DsoRules contract id — cannot submit.'
      );
    }

    return {
      voterPartyId,
      svPartyId,
      voteDelegationCid,
      dsoRulesCid,
      dsoRulesDisclosed: toDisclosure(dsoRulesContract),
    };
  };

  /**
   * Resolves the id held by the UI to the *current* VoteRequest contract.
   * DsoRules_CastVote archives and recreates the VoteRequest on every vote, so
   * stale ids fail with CONTRACT_NOT_FOUND.
   *
   * The UI always holds `trackingCid || contractId`, which is what Scan indexes
   * as `vote_request_tracking_cid`, so this lookup returns the live contract for
   * any open request. Non-404 Scan failures are rethrown so a 500/network blip
   * is not mistaken for "not found".
   */
  const resolveCurrentVoteRequest = async (
    routeId: string
  ): Promise<{ contractId: string; disclosed: DisclosedContractInput }> => {
    let contract: ScanContract | undefined;
    try {
      const response = await scanClient.lookupDsoRulesVoteRequest(routeId);
      contract = response.dso_rules_vote_request as ScanContract | undefined;
    } catch (error) {
      if (!isScanNotFound(error)) {
        throw error;
      }
    }

    if (!contract) {
      throw new VoteDelegationContextError(
        'This vote request is no longer open — it may have executed, expired, or been updated. Refresh and retry.'
      );
    }

    return {
      contractId: contract.contract_id,
      disclosed: toDisclosure(contract),
    };
  };

  return {
    submitCastVote: async ({
      voteRequestContractId,
      isAccepted,
      reasonUrl,
      reasonDescription,
    }): Promise<void> => {
      const context = await resolveContext('vote');
      const voteRequest = await resolveCurrentVoteRequest(voteRequestContractId);

      const params = buildVoteDelegationCastParams({
        voteRequestContractId: voteRequest.contractId,
        accepted: isAccepted,
        reasonUrl,
        reasonDescription,
        voteDelegationCid: context.voteDelegationCid,
        dsoRulesCid: context.dsoRulesCid,
        svPartyId: context.svPartyId,
        voterPartyId: context.voterPartyId,
        disclosedContracts: [context.dsoRulesDisclosed, voteRequest.disclosed],
      });

      try {
        await sdkClient.prepareExecuteAndWait(params);
      } catch (error) {
        throw await mapWalletError(error);
      }
    },

    submitCreateVoteRequest: async ({
      action,
      url,
      description,
      expiration,
      effectiveTime,
    }): Promise<void> => {
      const context = await resolveContext('proposal');

      const encodedExpiration = RelTime.encode(expiration) as { microseconds: string };
      const params = buildVoteDelegationRequestParams({
        action: ActionRequiringConfirmation.encode(action),
        reasonUrl: url,
        reasonDescription: description,
        voteRequestTimeoutMicroseconds: encodedExpiration.microseconds,
        targetEffectiveAt: effectiveTime?.toISOString(),
        voteDelegationCid: context.voteDelegationCid,
        dsoRulesCid: context.dsoRulesCid,
        svPartyId: context.svPartyId,
        voterPartyId: context.voterPartyId,
        disclosedContracts: [context.dsoRulesDisclosed],
      });

      try {
        await sdkClient.prepareExecuteAndWait(params);
      } catch (error) {
        throw await mapWalletError(error);
      }
    },
  };
}

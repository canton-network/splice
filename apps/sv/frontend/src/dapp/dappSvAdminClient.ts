// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as scanOpenapi from '@canton-network/scan-openapi';
import { Contract } from '@canton-network/splice-common-frontend-utils';
import * as svOpenapi from '@canton-network/sv-openapi';
import BigNumber from 'bignumber.js';

import { RelTime } from '@daml.js/daml-stdlib-DA-Time-Types-1.0.0/lib/DA/Time/Types/module';
import { DsoRules } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { SvAdminClient } from '../contexts/SvAdminServiceContext';
import { DappModeConfig } from '../utils/config';

export class DappModeUnsupportedError extends Error {
  constructor(method: string) {
    super(`${method} is not available in dApp mode`);
    this.name = 'DappModeUnsupportedError';
  }
}

const notAvailable = (method: string): Promise<never> =>
  Promise.reject(new DappModeUnsupportedError(method));

export interface DappSvAdminClientDeps {
  scanClient: scanOpenapi.ScanApi;
  dappMode: DappModeConfig;
  /** Wallet party currently connected through the gateway (VoteDelegation.voterParty). */
  voterPartyId?: string;
  isWalletConnected: boolean;
  /** Submission layer; wired to the wallet gateway (see dapp/voteDelegationSubmission.ts). */
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
 * dApp-mode implementation of the SvAdminClient interface: reads go to Scan
 * (identical shared OpenAPI schemas), governance submissions go through the
 * CIP-103 wallet gateway, and SV-node-only operations are unavailable.
 */
export function createDappSvAdminClient(deps: DappSvAdminClientDeps): SvAdminClient {
  const { scanClient, isWalletConnected, submitCastVote, submitCreateVoteRequest } = deps;

  const getActiveSynchronizerId = async (): Promise<string> => {
    const dsoInfo = await scanClient.getDsoInfo();
    const dsoRules = Contract.decodeOpenAPI(dsoInfo.dso_rules.contract, DsoRules);
    const synchronizerId = dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId;
    if (!synchronizerId) {
      throw new Error('DsoRules config does not expose an active synchronizer id');
    }
    return synchronizerId;
  };

  return {
    isAuthorized: async (): Promise<void> => {
      if (!isWalletConnected) {
        throw Object.assign(new Error('No wallet connected'), { code: 403 });
      }
    },

    createVoteRequest: async (
      requester,
      action,
      url,
      description,
      expiration,
      effectiveTime
    ): Promise<void> => {
      await submitCreateVoteRequest({
        requester,
        action,
        url,
        description,
        expiration,
        effectiveTime,
      });
    },

    castVote: async (
      voteRequestContractId,
      isAccepted,
      reasonUrl,
      reasonDescription
    ): Promise<void> => {
      await submitCastVote({ voteRequestContractId, isAccepted, reasonUrl, reasonDescription });
    },

    listDsoRulesVoteRequests: async (): Promise<svOpenapi.ListDsoRulesVoteRequestsResponse> => {
      return await scanClient.listDsoRulesVoteRequests();
    },

    listVoteRequestResults: async (
      limit,
      actionName,
      requester,
      effectiveFrom,
      effectiveTo,
      accepted,
      pageToken
    ): Promise<svOpenapi.ListDsoRulesVoteResultsResponse> => {
      return await scanClient.listVoteRequestResults({
        actionName,
        accepted,
        requester,
        effectiveFrom,
        effectiveTo,
        limit,
        pageToken,
      });
    },

    countVoteRequestResults: async (
      accepted,
      effectiveTo
    ): Promise<svOpenapi.CountVoteResultsResponse> => {
      return await scanClient.countVoteRequestResults({ accepted, effectiveTo });
    },

    getPreviousSvRewardWeight: async (
      svParty,
      effectiveBefore
    ): Promise<svOpenapi.PreviousSvRewardWeightResponse> => {
      return await scanClient.getPreviousSvRewardWeight({ svParty, effectiveBefore });
    },

    lookupDsoRulesVoteRequest: async (
      voteRequestContractId
    ): Promise<svOpenapi.LookupDsoRulesVoteRequestResponse> => {
      return await scanClient.lookupDsoRulesVoteRequest(voteRequestContractId);
    },

    listVoteRequestsByTrackingCid: async (
      voteRequestContractIds
    ): Promise<svOpenapi.ListVoteRequestByTrackingCidResponse> => {
      return await scanClient.listVoteRequestsByTrackingCid({
        vote_request_contract_ids: voteRequestContractIds,
      });
    },

    prepareValidatorOnboarding: () => notAvailable('prepareValidatorOnboarding'),
    listOngoingValidatorOnboardings: () => notAvailable('listOngoingValidatorOnboardings'),

    listValidatorLicenses: async (
      limit,
      after
    ): Promise<svOpenapi.ListValidatorLicensesResponse> => {
      return await scanClient.listValidatorLicenses(after, limit);
    },

    listAmuletPriceVotes: async (): Promise<svOpenapi.ListAmuletPriceVotesResponse> => {
      return await scanClient.listAmuletPriceVotes();
    },

    updateDesiredAmuletPrice: (_amuletPrice: BigNumber) => notAvailable('updateDesiredAmuletPrice'),
    listOpenMiningRounds: () => notAvailable('listOpenMiningRounds'),
    getCometBftNodeDebug: () => notAvailable('getCometBftNodeDebug'),
    getSequencerNodeStatus: () => notAvailable('getSequencerNodeStatus'),
    getMediatorNodeStatus: () => notAvailable('getMediatorNodeStatus'),

    featureSupport: async (): Promise<svOpenapi.FeatureSupportResponse> => {
      return await scanClient.featureSupport();
    },

    getPartyToParticipant: async (partyId): Promise<svOpenapi.GetPartyToParticipantResponseV1> => {
      const synchronizerId = await getActiveSynchronizerId();
      return await scanClient.getPartyToParticipantV1(synchronizerId, partyId);
    },

    listFeaturedAppRightsByProvider: async (
      providerPartyId
    ): Promise<svOpenapi.ListFeaturedAppRightsByProviderResponse> => {
      const response = await scanClient.listFeaturedAppRightsByProvider(providerPartyId);
      return { featured_app_rights: response.featured_apps };
    },

    lookupFeaturedAppRightByContractId: async (
      contractId
    ): Promise<svOpenapi.LookupFeaturedAppRightByContractIdResponse> => {
      const response = await scanClient.lookupFeaturedAppRightByContractId(contractId);
      return { featured_app_right: response.featured_app_right };
    },
  };
}

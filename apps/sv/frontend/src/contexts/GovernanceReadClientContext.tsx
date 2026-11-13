// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useSvClient } from '@canton-network/splice-common-frontend';
import { GetDsoInfoResponse } from '@canton-network/sv-openapi';
import React, { useContext, useMemo } from 'react';

import { SvAdminClient, useSvAdminClient } from './SvAdminServiceContext';

/**
 * The read-only subset of the SV app API that the governance pages need.
 * Operator mode serves it from the SV app; other modes can serve it from elsewhere.
 */
export type GovernanceReadClient = Pick<
  SvAdminClient,
  | 'listDsoRulesVoteRequests'
  | 'listVoteRequestResults'
  | 'countVoteRequestResults'
  | 'lookupDsoRulesVoteRequest'
  | 'listVoteRequestsByTrackingCid'
  | 'getPreviousSvRewardWeight'
  | 'lookupFeaturedAppRightByContractId'
  | 'listAmuletPriceVotes'
  | 'featureSupport'
> & { getDsoInfo: () => Promise<GovernanceDsoInfoResponse> };

export type GovernanceDsoInfoResponse = Omit<GetDsoInfoResponse, 'sv_user' | 'sv_party_id'> &
  Partial<Pick<GetDsoInfoResponse, 'sv_user' | 'sv_party_id'>>;

const GovernanceReadClientContext = React.createContext<GovernanceReadClient | undefined>(
  undefined
);

export const GovernanceReadClientProvider: React.FC<
  React.PropsWithChildren<{ client: GovernanceReadClient }>
> = ({ client, children }) => (
  <GovernanceReadClientContext.Provider value={client}>
    {children}
  </GovernanceReadClientContext.Provider>
);

export const useGovernanceReadClient: () => GovernanceReadClient = () => {
  const client = useContext<GovernanceReadClient | undefined>(GovernanceReadClientContext);
  if (!client) {
    throw new Error('Governance read client not initialized');
  }
  return client;
};

export const SvAppGovernanceReadClientProvider: React.FC<React.PropsWithChildren> = ({
  children,
}) => {
  const svAdminClient = useSvAdminClient();
  const svClient = useSvClient();

  const client: GovernanceReadClient = useMemo(
    () => ({
      listDsoRulesVoteRequests: svAdminClient.listDsoRulesVoteRequests,
      listVoteRequestResults: svAdminClient.listVoteRequestResults,
      countVoteRequestResults: svAdminClient.countVoteRequestResults,
      lookupDsoRulesVoteRequest: svAdminClient.lookupDsoRulesVoteRequest,
      listVoteRequestsByTrackingCid: svAdminClient.listVoteRequestsByTrackingCid,
      getPreviousSvRewardWeight: svAdminClient.getPreviousSvRewardWeight,
      lookupFeaturedAppRightByContractId: svAdminClient.lookupFeaturedAppRightByContractId,
      listAmuletPriceVotes: svAdminClient.listAmuletPriceVotes,
      featureSupport: svAdminClient.featureSupport,
      getDsoInfo: svClient.getDsoInfo,
    }),
    [svAdminClient, svClient]
  );

  return <GovernanceReadClientProvider client={client}>{children}</GovernanceReadClientProvider>;
};

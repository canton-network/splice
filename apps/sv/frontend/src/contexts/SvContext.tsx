// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useSvClient, useUserState, DsoInfo } from '@canton-network/splice-common-frontend';
import { Contract } from '@canton-network/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules';
import { SvNodeState } from '@daml.js/splice-dso-governance/lib/Splice/DSO/SvState';
import { DsoRules } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

import { useDappDsoInfos } from '../dapp/useDappDsoInfos';
import { useWalletSessionOptional } from '../dapp/WalletSessionContext';
import { useDappModeConfig } from '../utils';
import { useSvAdminClient } from './SvAdminServiceContext';

const useBackendDsoInfos = (): UseQueryResult<DsoInfo> => {
  const { getDsoInfo } = useSvClient();
  // The SV app's /v1/dso endpoint requires authentication
  const { userAccessToken } = useUserState();
  return useQuery({
    enabled: !!userAccessToken,
    queryKey: ['getDsoInfo', DsoRules, AmuletRules],
    queryFn: async () => {
      const resp = await getDsoInfo();
      return {
        svUser: resp.sv_user,
        svPartyId: resp.sv_party_id,
        dsoPartyId: resp.dso_party_id,
        votingThreshold: BigInt(resp.voting_threshold),
        amuletRules: Contract.decodeOpenAPI(resp.amulet_rules.contract, AmuletRules),
        dsoRules: Contract.decodeOpenAPI(resp.dso_rules.contract, DsoRules),
        nodeStates: resp.sv_node_states.map(c => Contract.decodeOpenAPI(c.contract, SvNodeState)),
      };
    },
  });
};

export const useDsoInfos = (): UseQueryResult<DsoInfo> => {
  const dappMode = useDappModeConfig();
  // The mode is fixed for the lifetime of the page (config is read once from
  // window.splice_config), so the branch below never changes hook order
  // between renders.
  if (dappMode) {
    // eslint-disable-next-line react-hooks/rules-of-hooks
    const walletSession = useWalletSessionOptional();
    // eslint-disable-next-line react-hooks/rules-of-hooks
    return useDappDsoInfos(walletSession?.svPartyId);
  }
  // eslint-disable-next-line react-hooks/rules-of-hooks
  return useBackendDsoInfos();
};

// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export const useFeatureSupport = (): UseQueryResult<{}> => {
  const { featureSupport } = useSvAdminClient();
  return useQuery({
    queryKey: ['featureSupport'],
    queryFn: async () => {
      await featureSupport();
      return {};
    },
  });
};

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DsoInfo } from '@canton-network/splice-common-frontend';
import { useScanClient } from '@canton-network/splice-common-frontend/scan-api';
import { Contract } from '@canton-network/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules';
import { SvNodeState } from '@daml.js/splice-dso-governance/lib/Splice/DSO/SvState';
import { DsoRules } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

/**
 * dApp-mode DsoInfo, read from Scan /v0/dso. Scan reports the party of the SV
 * sponsoring that Scan instance, so the ACS-discovered delegating SV party
 * takes precedence for vote attribution and highlighting.
 */
export const useDappDsoInfos = (svPartyIdOverride?: string): UseQueryResult<DsoInfo> => {
  const scanClient = useScanClient();
  return useQuery({
    queryKey: ['getDsoInfo', 'dappMode', svPartyIdOverride, DsoRules, AmuletRules],
    queryFn: async () => {
      const resp = await scanClient.getDsoInfo();
      return {
        svUser: resp.sv_user,
        svPartyId: svPartyIdOverride ?? resp.sv_party_id,
        dsoPartyId: resp.dso_party_id,
        votingThreshold: BigInt(resp.voting_threshold),
        amuletRules: Contract.decodeOpenAPI(resp.amulet_rules.contract, AmuletRules),
        dsoRules: Contract.decodeOpenAPI(resp.dso_rules.contract, DsoRules),
        nodeStates: resp.sv_node_states.map(c => Contract.decodeOpenAPI(c.contract, SvNodeState)),
      };
    },
  });
};

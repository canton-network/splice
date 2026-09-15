// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useScanClient } from '@canton-network/splice-common-frontend/scan-api';
import React, { useMemo } from 'react';

import { SvAdminClient, SvAdminContext } from '../contexts/SvAdminServiceContext';
import { useDappModeConfig } from '../utils';
import { useWalletSession } from './WalletSessionContext';
import { getDappSdkClient } from './dappSdkClient';
import { createDappSvAdminClient } from './dappSvAdminClient';
import { createVoteDelegationSubmission } from './voteDelegationSubmission';

/**
 * Provides the SvAdminClient interface backed by Scan reads and CIP-103
 * submissions. Mounted in place of SvAdminClientProvider when dApp mode is on,
 * so the governance UI works unchanged.
 */
export const DappSvAdminClientProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  const dappMode = useDappModeConfig();
  if (!dappMode) {
    throw new Error('DappSvAdminClientProvider requires dappMode to be enabled in the app config');
  }
  const scanClient = useScanClient();
  const walletSession = useWalletSession();
  const voterPartyId = walletSession.voterPartyId;
  const svPartyId = walletSession.svPartyId;
  const voteDelegationCid = walletSession.voteDelegationCid;
  const isWalletConnected = walletSession.status === 'connected';
  const { scanUrl, cip103RpcUrl } = dappMode;

  const client: SvAdminClient = useMemo(() => {
    const dappModeConfig = {
      scanUrl,
      cip103RpcUrl,
    };
    const submission = createVoteDelegationSubmission({
      scanClient,
      sdkClient: getDappSdkClient(cip103RpcUrl),
      getVoterPartyId: () => voterPartyId,
      getSvPartyId: () => svPartyId,
      getVoteDelegationCid: () => voteDelegationCid,
    });
    return createDappSvAdminClient({
      scanClient,
      dappMode: dappModeConfig,
      voterPartyId,
      isWalletConnected,
      submitCastVote: submission.submitCastVote,
      submitCreateVoteRequest: submission.submitCreateVoteRequest,
    });
  }, [
    scanClient,
    scanUrl,
    cip103RpcUrl,
    voterPartyId,
    svPartyId,
    voteDelegationCid,
    isWalletConnected,
  ]);

  return <SvAdminContext.Provider value={client}>{children}</SvAdminContext.Provider>;
};

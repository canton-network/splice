// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AuthProvider,
  SvClientProvider,
  theme,
  UserProvider,
} from '@canton-network/splice-common-frontend';
import { ScanClientProvider } from '@canton-network/splice-common-frontend/scan-api';
import { replaceEqualDeep } from '@canton-network/splice-common-frontend-utils';
import { ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useNavigate } from 'react-router';
import { Toaster } from 'sonner';

import { DappSvAdminClientProvider } from '../../dapp/DappSvAdminClientProvider';
import { WalletSessionProvider } from '../../dapp/WalletSessionContext';
import { SvAppVotesHooksProvider } from '../../contexts/SvAppVotesHooksContext';
import { SvConfigProvider, useSvConfig } from '../../utils';
import { dappScanUrl } from './dappConfig';

const testQueryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchInterval: 500,
      structuralSharing: replaceEqualDeep,
      retry: false,
      gcTime: 0,
    },
  },
});

/**
 * Mirror of the App.tsx provider tree in dApp mode. Test files using this
 * wrapper must call enableDappModeConfig() and vi.mock('../../dapp/dappSdkClient').
 */
const DappWrapperProviders: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const config = useSvConfig();
  const navigate = useNavigate();

  return (
    <ThemeProvider theme={theme}>
      <AuthProvider authConf={config.auth} redirect={(path: string) => navigate(path)}>
        <QueryClientProvider client={testQueryClient}>
          <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
            <SvClientProvider url={config.services.sv.url}>
              <SvAppVotesHooksProvider>
                <ScanClientProvider baseScanUrl={dappScanUrl}>
                  <WalletSessionProvider>
                    <DappSvAdminClientProvider>{children}</DappSvAdminClientProvider>
                  </WalletSessionProvider>
                </ScanClientProvider>
              </SvAppVotesHooksProvider>
            </SvClientProvider>
          </UserProvider>
        </QueryClientProvider>
      </AuthProvider>
    </ThemeProvider>
  );
};

export const DappWrapper: React.FC<{
  children: React.ReactNode;
  initialEntries?: string[];
}> = ({ children, initialEntries }) => {
  return (
    <MemoryRouter initialEntries={initialEntries}>
      <SvConfigProvider>
        <DappWrapperProviders children={children} />
        <Toaster richColors />
      </SvConfigProvider>
    </MemoryRouter>
  );
};

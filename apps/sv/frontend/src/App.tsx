// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AuthProvider,
  ErrorBoundary,
  ErrorRouterPage,
  QueryDevtools,
  UserProvider,
  retryQuery,
  theme,
  SvClientProvider,
} from '@canton-network/splice-common-frontend';
import { ScanClientProvider } from '@canton-network/splice-common-frontend/scan-api';
import { replaceEqualDeep } from '@canton-network/splice-common-frontend-utils';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Helmet, HelmetProvider } from 'react-helmet-async';
import {
  Navigate,
  Route,
  RouterProvider,
  createBrowserRouter,
  createRoutesFromElements,
  useNavigate,
} from 'react-router';

import { CssBaseline, ThemeProvider } from '@mui/material';
import { LocalizationProvider } from '@mui/x-date-pickers';
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs';

import { SvAdminClientProvider } from './contexts/SvAdminServiceContext';
import { SvAppVotesHooksProvider } from './contexts/SvAppVotesHooksContext';
import { DappSvAdminClientProvider } from './dapp/DappSvAdminClientProvider';
import { WalletSessionProvider } from './dapp/WalletSessionContext';
import { useDappModeConfig } from './utils';
import AmuletPrice from './routes/amuletPrice';
import AuthCheck from './routes/authCheck';
import Dso from './routes/dso';
import Root from './routes/root';
import ValidatorOnboarding from './routes/validatorOnboarding';
import Voting from './routes/voting';
import { useConfigPollInterval, useSvConfig } from './utils';
import { Governance } from './routes/governance';
import { VoteRequestDetails } from './routes/voteRequestDetails';
import { CreateProposal } from './routes/createProposal';
import DelegateElection from './routes/delegateElection';

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const config = useSvConfig();
  const dappMode = useDappModeConfig();
  const refetchInterval = useConfigPollInterval();
  const navigate = useNavigate();

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        refetchInterval,
        structuralSharing: replaceEqualDeep,
        retry: retryQuery,
      },
    },
  });

  // In dApp mode the SV backend is not used: reads come from Scan and
  // submissions go through the CIP-103 dApp API.
  const adminClientProvider = dappMode ? (
    <ScanClientProvider baseScanUrl={dappMode.scanUrl}>
      <WalletSessionProvider>
        <DappSvAdminClientProvider>{children}</DappSvAdminClientProvider>
      </WalletSessionProvider>
    </ScanClientProvider>
  ) : (
    <SvAdminClientProvider url={config.services.sv.url}>{children}</SvAdminClientProvider>
  );

  const appProviders = (
    <QueryClientProvider client={queryClient}>
      <QueryDevtools />
      <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
        <SvClientProvider url={config.services.sv.url}>
          <SvAppVotesHooksProvider>{adminClientProvider}</SvAppVotesHooksProvider>
        </SvClientProvider>
      </UserProvider>
    </QueryClientProvider>
  );

  // dApp mode authenticates via CIP-103 wallet connect. Skip AuthProvider so
  // oidc-client-ts automaticSilentRenew does not hit a missing authority
  // (docker image auth is RS-256). UserProvider tolerates a missing OIDC context.
  return (
    <LocalizationProvider dateAdapter={AdapterDayjs}>
      {dappMode ? (
        appProviders
      ) : (
        <AuthProvider authConf={config.auth} redirect={(path: string) => navigate(path)}>
          {appProviders}
        </AuthProvider>
      )}
    </LocalizationProvider>
  );
};

const App: React.FC = () => {
  const config = useSvConfig();
  const router = createBrowserRouter(
    createRoutesFromElements(
      <Route
        errorElement={<ErrorRouterPage />}
        element={
          <Providers>
            <ThemeProvider theme={theme}>
              <CssBaseline />
              <AuthCheck authConfig={config.auth} testAuthConfig={config.testAuth} />
            </ThemeProvider>
          </Providers>
        }
      >
        <Route path="/" element={<Root />}>
          <Route index element={<Dso />} />
          <Route path="dso" element={<Dso />} />
          <Route path="validator-onboarding" element={<ValidatorOnboarding />} />
          <Route path="amulet-price" element={<AmuletPrice />} />
          <Route path="delegate-election" element={<DelegateElection />} />
          <Route path="governance-old" element={<Voting />} />
          <Route path="votes" element={<Navigate to="/governance-old" replace />} />

          <Route path="governance" element={<Navigate to="/governance/proposals" replace />} />
          <Route path="governance/proposals" element={<Governance />} />
          <Route path="governance/proposals/create" element={<CreateProposal />} />
          <Route path="governance/proposals/:contractId" element={<VoteRequestDetails />} />
        </Route>
      </Route>
    )
  );

  return (
    <ErrorBoundary>
      <HelmetProvider>
        <Helmet>
          <title>Supervalidator Operations</title>
          <meta name="description" content="Supervalidator Operations" />
          <link rel="icon" href={config.spliceInstanceNames.networkFaviconUrl} />
        </Helmet>
        <RouterProvider router={router} />
      </HelmetProvider>
    </ErrorBoundary>
  );
};

export default App;

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { useMemo, useState } from 'react';
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
import { ScanClientProvider, useScanClient } from '@canton-network/splice-common-frontend/scan-api';
import { replaceEqualDeep } from '@canton-network/splice-common-frontend-utils';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Helmet, HelmetProvider } from 'react-helmet-async';
import {
  Navigate,
  Outlet,
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
import {
  GovernanceReadClientProvider,
  SvAppGovernanceReadClientProvider,
} from './contexts/GovernanceReadClientContext';
import { SvAppVotesHooksProvider } from './contexts/SvAppVotesHooksContext';
import AmuletPrice from './routes/amuletPrice';
import AuthCheck from './routes/authCheck';
import Dso from './routes/dso';
import Root from './routes/root';
import ValidatorOnboarding from './routes/validatorOnboarding';
import {
  DappModeConfig,
  getDappModeConfig,
  requireOperatorConfig,
  SvConfig,
  useConfigPollInterval,
  useDappModeConfig,
  useSvConfig,
} from './utils';
import { scanGovernanceReadClient } from './dapp/scanGovernanceReadClient';
import { Governance } from './routes/governance';
import { VoteRequestDetails } from './routes/voteRequestDetails';
import { CreateProposal } from './routes/createProposal';
import DelegateElection from './routes/delegateElection';

const makeQueryClient = (refetchInterval: number): QueryClient =>
  new QueryClient({
    defaultOptions: {
      queries: {
        refetchInterval,
        structuralSharing: replaceEqualDeep,
        retry: retryQuery,
      },
    },
  });

const OperatorProviders: React.FC<React.PropsWithChildren> = ({ children }) => {
  const config = useSvConfig();
  const refetchInterval = useConfigPollInterval();
  const navigate = useNavigate();
  // refetchInterval comes from config and does not change at runtime, so create the client once.
  const [queryClient] = useState(() => makeQueryClient(refetchInterval));
  const { auth, testAuth } = requireOperatorConfig(config);

  return (
    <LocalizationProvider dateAdapter={AdapterDayjs}>
      <AuthProvider authConf={auth} redirect={(path: string) => navigate(path)}>
        <QueryClientProvider client={queryClient}>
          <QueryDevtools />
          <UserProvider authConf={auth} testAuthConf={testAuth}>
            <SvClientProvider url={config.services.sv.url}>
              <SvAppVotesHooksProvider>
                <SvAdminClientProvider url={config.services.sv.url}>
                  <SvAppGovernanceReadClientProvider>{children}</SvAppGovernanceReadClientProvider>
                </SvAdminClientProvider>
              </SvAppVotesHooksProvider>
            </SvClientProvider>
          </UserProvider>
        </QueryClientProvider>
      </AuthProvider>
    </LocalizationProvider>
  );
};

const ScanGovernanceReadClientProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  const scan = useScanClient();
  const client = useMemo(() => scanGovernanceReadClient(scan), [scan]);
  return <GovernanceReadClientProvider client={client}>{children}</GovernanceReadClientProvider>;
};

/** dApp mode: no login, no SV app; governance reads come from Scan. */
const DappProviders: React.FC<React.PropsWithChildren<{ dappMode: DappModeConfig }>> = ({
  dappMode,
  children,
}) => {
  const refetchInterval = useConfigPollInterval();
  // refetchInterval comes from config and does not change at runtime, so create the client once.
  const [queryClient] = useState(() => makeQueryClient(refetchInterval));

  return (
    <LocalizationProvider dateAdapter={AdapterDayjs}>
      <QueryClientProvider client={queryClient}>
        <QueryDevtools />
        <ScanClientProvider baseScanUrl={dappMode.scanUrl}>
          <ScanGovernanceReadClientProvider>
            <SvAppVotesHooksProvider>{children}</SvAppVotesHooksProvider>
          </ScanGovernanceReadClientProvider>
        </ScanClientProvider>
      </QueryClientProvider>
    </LocalizationProvider>
  );
};

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const dappMode = useDappModeConfig();
  return dappMode ? (
    <DappProviders dappMode={dappMode}>{children}</DappProviders>
  ) : (
    <OperatorProviders>{children}</OperatorProviders>
  );
};

const createRouter = (config: SvConfig) => {
  const dappMode = getDappModeConfig(config);
  const operator = dappMode ? undefined : requireOperatorConfig(config);
  const toGovernance = <Navigate to="/governance/proposals" replace />;
  // dApp mode serves only the read-only governance pages; every operator-only page redirects there.
  const operatorOnly = (page: React.ReactElement) => (dappMode ? toGovernance : page);

  return createBrowserRouter(
    createRoutesFromElements(
      <Route
        errorElement={<ErrorRouterPage />}
        element={
          <Providers>
            <ThemeProvider theme={theme}>
              <CssBaseline />
              {operator ? (
                <AuthCheck authConfig={operator.auth} testAuthConfig={operator.testAuth} />
              ) : (
                <Outlet />
              )}
            </ThemeProvider>
          </Providers>
        }
      >
        <Route path="/" element={<Root />}>
          <Route index element={operatorOnly(<Dso />)} />
          <Route path="dso" element={operatorOnly(<Dso />)} />
          <Route path="validator-onboarding" element={operatorOnly(<ValidatorOnboarding />)} />
          <Route path="amulet-price" element={operatorOnly(<AmuletPrice />)} />
          <Route path="delegate-election" element={operatorOnly(<DelegateElection />)} />
          <Route path="votes" element={toGovernance} />

          <Route path="governance" element={toGovernance} />
          <Route path="governance/proposals" element={<Governance />} />
          <Route path="governance/proposals/create" element={operatorOnly(<CreateProposal />)} />
          <Route path="governance/proposals/:contractId" element={<VoteRequestDetails />} />
          {/* Operator mode keeps the router's default not-found error page for unknown paths. */}
          {dappMode && <Route path="*" element={toGovernance} />}
        </Route>
      </Route>
    )
  );
};

const App: React.FC = () => {
  const config = useSvConfig();
  // The parsed config does not change at runtime, so the router is only rebuilt if the config object does.
  const router = useMemo(() => createRouter(config), [config]);

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

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  AuthProvider,
  SvClientProvider,
  theme,
  UserProvider,
} from '@canton-network/splice-common-frontend';
import { replaceEqualDeep } from '@canton-network/splice-common-frontend-utils';
import { ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router';
import { Toaster } from 'sonner';
import { expect, onTestFinished } from 'vitest';
import App from '../App';
import { SvAdminClientProvider } from '../contexts/SvAdminServiceContext';
import { SvAppGovernanceReadClientProvider } from '../contexts/GovernanceReadClientContext';
import { SvAppVotesHooksProvider } from '../contexts/SvAppVotesHooksContext';
import { requireOperatorConfig, SvConfigProvider, useSvConfig } from '../utils';
import { UserEvent } from '@testing-library/user-event';
import { scanUrl } from './setup/setup';

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

const WrapperProviders: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const config = useSvConfig();
  const { auth, testAuth } = requireOperatorConfig(config);
  const navigate = useNavigate();

  return (
    <ThemeProvider theme={theme}>
      <AuthProvider authConf={auth} redirect={(path: string) => navigate(path)}>
        <QueryClientProvider client={testQueryClient}>
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
    </ThemeProvider>
  );
};

export const Wrapper: React.FC<{
  children: React.ReactNode;
  initialEntries?: string[];
}> = ({ children, initialEntries }) => {
  return (
    <MemoryRouter initialEntries={initialEntries}>
      <SvConfigProvider>
        <WrapperProviders children={children} />
        <Toaster richColors />
      </SvConfigProvider>
    </MemoryRouter>
  );
};

export async function navigateToGovernancePage(user: UserEvent): Promise<void> {
  expect(await screen.findByTestId('navlink-governance')).toBeInTheDocument();
  await user.click(screen.getByTestId('navlink-governance'));
}

/** Renders <App/> with window.splice_config.dappMode = { enabled: true, scanUrl } for the test, restoring the config afterwards. */
export const renderDappApp: (initialPath?: string) => void = (initialPath = '/') => {
  // Mutate in place: the common config reader captures the window.splice_config object at module
  // load, so assigning a new object would not be seen by SvConfigProvider.
  const config = window.splice_config as typeof window.splice_config & { dappMode?: unknown };
  const hadDappMode = 'dappMode' in config;
  const previousDappMode = config.dappMode;
  config.dappMode = { enabled: true, scanUrl };
  onTestFinished(() => {
    if (hadDappMode) {
      config.dappMode = previousDappMode;
    } else {
      delete config.dappMode;
    }
  });
  window.history.pushState({}, '', initialPath);
  render(
    <SvConfigProvider>
      <App />
    </SvConfigProvider>
  );
};

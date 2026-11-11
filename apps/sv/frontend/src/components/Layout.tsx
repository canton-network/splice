// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { Loading, UserContext, useVotesHooks } from '@canton-network/splice-common-frontend';

import { Alert, AlertTitle, Box, Container, GlobalStyles } from '@mui/material';
import { useLocation } from 'react-router';

import { useFeatureSupport } from '../contexts/SvContext';
import { useDappModeConfig, useSvConfig } from '../utils';
import { partyIdScrollGlobalStyles } from './ui/identifierStyles';
import PartyIdScrollTracks from './PartyIdScrollTracks';
import SvNavigationShell from './layout/SvNavigationShell';
import { SvNavLinkItem } from './layout/SvNavLink';
import { CONTENT_MAX_WIDTH, layoutTokens, PAGE_PX } from '../theme/tokens';
import NetworkBanner from './layout/NetworkBanner';

interface LayoutProps {
  children: React.ReactNode;
}

const pathnameToPageName = (pathname: string, amuletName: string): string => {
  if (pathname.startsWith('/governance')) {
    return 'Governance';
  }
  if (pathname.startsWith('/validator-onboarding')) {
    return 'Validators';
  }
  if (pathname.startsWith('/amulet-price')) {
    return `${amuletName} Price`;
  }
  return 'Global Synchronizer Information';
};

/** Figma content-width 1583px centered — nav uses full width inside Navigation shell */
const contentShellSx = {
  maxWidth: CONTENT_MAX_WIDTH,
  mx: 'auto',
  px: PAGE_PX,
  width: '100%',
};

const pageSx = {
  bgcolor: layoutTokens.page,
  display: 'flex',
  flexDirection: 'column' as const,
  minHeight: '100vh',
};

const Layout: React.FC<LayoutProps> = ({ children }) => {
  const config = useSvConfig();
  const dappMode = useDappModeConfig();
  // dApp mode mounts no UserProvider (no login, no logout); operator mode always does.
  const userState = React.useContext(UserContext);
  if (dappMode === undefined && userState === undefined) {
    throw new Error('UserProvider must be mounted in operator mode');
  }
  const location = useLocation();
  const featureSupport = useFeatureSupport();

  const votesHooks = useVotesHooks();
  const svInfoQuery = votesHooks.useSvInfo();
  const listVoteRequestsQuery = votesHooks.useListDsoRulesVoteRequests();
  const svPartyId = svInfoQuery.data?.svPartyId;
  const actionsPending =
    svPartyId === undefined
      ? undefined
      : listVoteRequestsQuery.data?.filter(
          vr => vr.payload.votes.entriesArray().find(e => e[1].sv === svPartyId) === undefined
        );

  // dApp mode has no auth flow that would surface a failing backend, so show Scan errors explicitly,
  // but only while nothing has loaded yet: a failed poll after a successful load keeps the last
  // data and must not replace the UI. react-query resets a query that never had data back to
  // 'pending' on every poll refetch, so isError alone would flip back to the spinner;
  // errorUpdateCount survives that reset. Checked before isLoading.
  const scanFailed =
    dappMode !== undefined &&
    featureSupport.data === undefined &&
    (featureSupport.isError || featureSupport.errorUpdateCount > 0);
  if (scanFailed) {
    return (
      <Box sx={pageSx}>
        <Alert severity="error" sx={{ m: PAGE_PX }}>
          <AlertTitle>Cannot reach Scan</AlertTitle>
          The SV UI could not load data from Scan at {dappMode.scanUrl}. Check that the URL is
          correct and reachable from your browser.
        </Alert>
      </Box>
    );
  }

  if (featureSupport.isLoading) {
    return <Loading />;
  }

  const governanceLink: SvNavLinkItem = {
    name: 'Governance',
    path: '/governance',
    end: false,
    badgeCount: actionsPending?.length,
  };
  const navLinks: SvNavLinkItem[] = dappMode
    ? [governanceLink]
    : [
        { name: 'Global Synchronizer Information', path: '/dso', alsoActiveFor: ['/'] },
        governanceLink,
        { name: `${config.spliceInstanceNames.amuletName} Price`, path: '/amulet-price' },
        { name: 'Validators', path: '/validator-onboarding' },
      ];

  const pageName = pathnameToPageName(location.pathname, config.spliceInstanceNames.amuletName);

  return (
    <Box sx={pageSx}>
      <GlobalStyles styles={partyIdScrollGlobalStyles} />
      <PartyIdScrollTracks />
      <NetworkBanner />
      <SvNavigationShell navLinks={navLinks} onLogout={userState?.logout} pageName={pageName} />

      <Box sx={{ flex: 1, pb: 3 }}>
        <Container maxWidth={false} sx={contentShellSx}>
          {children}
        </Container>
      </Box>
    </Box>
  );
};

export default Layout;

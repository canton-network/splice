// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box, Stack, Typography } from '@mui/material';

import { HEADER_PB, layoutTokens, PAGE_PX } from '../../theme/tokens';
import { useNetworkInstanceName } from '../../hooks';
import SvTopNav from './SvTopNav';
import { SvNavLinkItem } from './SvNavLink';

interface SvNavigationShellProps {
  navLinks: SvNavLinkItem[];
  onLogout: () => void;
  pageName: string;
}

/**
 * Figma "Navigation" component — network banner above the nav row.
 * Dev Mode: padding-bottom 64px, background #272727.
 */
const SvNavigationShell: React.FC<SvNavigationShellProps> = ({ navLinks, onLogout, pageName }) => {
  const networkInstanceName = useNetworkInstanceName();
  const knownColors = ['mainnet', 'testnet', 'devnet', 'scratchnet'];
  const networkInstanceNameColor = knownColors.includes(networkInstanceName.toLowerCase())
    ? `colors.${networkInstanceName.toLowerCase()}`
    : 'colors.neutral.30';

  return (
    <Box
      data-component="navigation"
      data-page={pageName}
      sx={{
        display: 'flex',
        flexDirection: 'column',
        pb: HEADER_PB,
        bgcolor: layoutTokens.navBackground,
        width: '100%',
      }}
    >
      <Stack
        direction="row"
        alignItems="center"
        justifyContent="center"
        sx={{
          position: 'sticky',
          top: 0,
          zIndex: 1100,
          pointerEvents: 'none',
          backgroundColor: networkInstanceNameColor,
          color: 'black',
          height: '50px',
          width: '100%',
        }}
      >
        <Typography id="network-instance-name" data-testid="network-instance-name" variant="h6">
          <b>You are on {networkInstanceName} </b>
        </Typography>
      </Stack>
      <Box sx={{ px: PAGE_PX, width: '100%' }}>
        <SvTopNav navLinks={navLinks} onLogout={onLogout} />
      </Box>
    </Box>
  );
};

export default SvNavigationShell;

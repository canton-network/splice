// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box } from '@mui/material';

import { HEADER_PB, HEADER_PT, layoutTokens, PAGE_PX } from '../../theme/tokens';
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
  return (
    <Box
      data-component="navigation"
      data-page={pageName}
      sx={{
        display: 'flex',
        flexDirection: 'column',
        pb: HEADER_PB,
        pt: HEADER_PT,
        bgcolor: layoutTokens.navBackground,
        width: '100%',
      }}
    >
      <Box sx={{ px: PAGE_PX, width: '100%' }}>
        <SvTopNav navLinks={navLinks} onLogout={onLogout} />
      </Box>
    </Box>
  );
};

export default SvNavigationShell;

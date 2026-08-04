// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box, Stack, Typography } from '@mui/material';

import {
  BRAND_TITLE,
  layoutTokens,
  NAV_BRAND_GAP,
  NAV_GAP,
  NAV_PILL_PX,
  NAV_ROW_MIN_HEIGHT,
} from '../../theme/tokens';
import LogoutButton from './LogoutButton';
import SvNavLink, { SvNavLinkItem } from './SvNavLink';

interface SvTopNavProps {
  navLinks: SvNavLinkItem[];
  onLogout: () => void;
}

/**
 * Figma nav row (Frame11): brand pinned left, a fixed 145px gap (`NAV_BRAND_GAP`)
 * to the nav cluster, then a flexible spacer that pushes logout to the pinned
 * right edge. The fixed left gap matches the Dev Mode measurement exactly; the
 * flexible right gap lets logout track the row's right edge at any viewport width.
 */
const SvTopNav: React.FC<SvTopNavProps> = ({ navLinks, onLogout }) => (
  <Stack
    direction="row"
    alignItems="center"
    flexWrap="wrap"
    sx={{
      width: '100%',
      minHeight: NAV_ROW_MIN_HEIGHT,
      rowGap: NAV_GAP,
    }}
  >
    <Box sx={{ flexShrink: 0, p: NAV_PILL_PX }}>
      <Typography
        id="app-title"
        data-testid="app-title"
        sx={{
          fontFamily: layoutTokens.fontBrand,
          fontSize: '1.25rem',
          fontWeight: 500,
          /**
           * Figma Dev Mode — brand box measures 324x24 at 20px font size, i.e. 120%
           * line-height (24px), not 100%. Unitless `1.2` scales with fontSize so it
           * stays correct if the font size ever changes.
           */
          lineHeight: 1.2,
          /** Figma: Letter spacing 0px — Typography's body1 default (0.00938em) otherwise leaks in. */
          letterSpacing: 0,
          fontFeatureSettings: "'liga' off, 'clig' off",
          color: layoutTokens.lightText,
        }}
      >
        {BRAND_TITLE}
      </Typography>
    </Box>

    <Box sx={{ width: NAV_BRAND_GAP, flexShrink: 0 }} />

    <Stack
      direction="row"
      flexWrap="wrap"
      alignItems="center"
      sx={{ gap: NAV_GAP, rowGap: NAV_GAP }}
    >
      {navLinks.map(link => (
        <SvNavLink key={link.path} link={link} />
      ))}
    </Stack>

    <Box sx={{ flexGrow: 1, minWidth: 16 }} />

    <LogoutButton onLogout={onLogout} />
  </Stack>
);

export default SvTopNav;

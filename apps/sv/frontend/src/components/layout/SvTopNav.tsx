// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box, Stack, Typography } from '@mui/material';

import {
  BRAND_TITLE,
  layoutTokens,
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
 * Nav row: brand (left, intrinsic width) · flex spacer · nav cluster ·
 * flex spacer · logout (right, intrinsic width). Equal spacers center the pills
 * in the gap between brand and logout — not in the full viewport.
 */
const SvTopNav: React.FC<SvTopNavProps> = ({ navLinks, onLogout }) => (
  <Box
    data-testid="sv-top-nav"
    sx={{
      display: 'flex',
      flexWrap: 'wrap',
      width: '100%',
      minHeight: NAV_ROW_MIN_HEIGHT,
      alignItems: 'center',
      columnGap: 0,
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

    <Box
      aria-hidden
      data-testid="sv-top-nav-spacer-start"
      sx={{
        flex: '1 1 0',
        minWidth: 16,
        // On narrow rows, drop side spacers so the nav can sit on its own centered row.
        '@media (max-width: 1400px)': { display: 'none' },
      }}
    />

    <Stack
      direction="row"
      flexWrap="wrap"
      alignItems="center"
      justifyContent="center"
      data-testid="sv-top-nav-links"
      sx={{
        flexShrink: 0,
        gap: NAV_GAP,
        rowGap: NAV_GAP,
        minWidth: 0,
        '@media (max-width: 1400px)': {
          flex: '1 1 100%',
          order: 3,
        },
      }}
    >
      {navLinks.map(link => (
        <SvNavLink key={link.path} link={link} />
      ))}
    </Stack>

    <Box
      aria-hidden
      data-testid="sv-top-nav-spacer-end"
      sx={{
        flex: '1 1 0',
        minWidth: 16,
        '@media (max-width: 1400px)': { display: 'none' },
      }}
    />

    <Box
      sx={{
        flexShrink: 0,
        '@media (max-width: 1400px)': {
          marginLeft: 'auto',
        },
      }}
    >
      <LogoutButton onLogout={onLogout} />
    </Box>
  </Box>
);

export default SvTopNav;

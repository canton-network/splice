// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box, Typography } from '@mui/material';

import { layoutTokens, navItemTypography, NAV_PILL_PX } from '../../theme/tokens';
import LogoutIcon from './LogoutIcon';

interface LogoutButtonProps {
  onLogout: () => void;
}

/**
 * Figma Dev Mode — content box 66x17, 10px padding on each side, gap-2.5 (10px)
 * between icon and label. Plain `Box component="button"` (matching `SvNavLink`'s
 * pattern) instead of MUI `Button` — MUI's own min-height/padding/ripple defaults
 * previously inflated this to ~89.78x38 despite the padding value being correct.
 */
const LogoutButton: React.FC<LogoutButtonProps> = ({ onLogout }) => (
  <Box
    component="button"
    id="logout-button"
    data-testid="logout-button"
    onClick={onLogout}
    sx={{
      display: 'inline-flex',
      alignItems: 'center',
      gap: '10px',
      p: NAV_PILL_PX,
      flexShrink: 0,
      border: 'none',
      background: 'none',
      cursor: 'pointer',
      color: layoutTokens.lightText,
      '&:hover': { opacity: 0.85 },
      '&:focus': { outline: 'none' },
      '&:focus-visible': {
        outline: '2px solid',
        outlineColor: layoutTokens.navActiveOutline,
        outlineOffset: '2px',
      },
    }}
  >
    <LogoutIcon />
    <Typography
      component="span"
      sx={{
        fontFamily: layoutTokens.fontUi,
        fontSize: '0.875rem',
        fontWeight: 700,
        /** Figma: Logout label is text-white, unlike the text-neutral-200 used elsewhere in the nav. */
        color: 'common.white',
        ...navItemTypography,
        /**
         * Figma Dev Mode — the Logout label's own CSS export is `line-height: normal`,
         * unlike nav pills/banner which are explicitly 140%. Overrides the shared
         * `navItemTypography.lineHeight` (1.4) for this element specifically.
         */
        lineHeight: 'normal',
      }}
    >
      Logout
    </Typography>
  </Box>
);

export default LogoutButton;

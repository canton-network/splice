// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { NavLink } from 'react-router';

import { Box } from '@mui/material';

import { layoutTokens, navItemTypography, NAV_PILL_PX } from '../../theme/tokens';
import NavAttentionIcon from './NavAttentionIcon';
import NavCountBadge from './NavCountBadge';

export interface SvNavLinkItem {
  name: string;
  path: string;
  badgeCount?: number;
  hasAlert?: boolean;
  /** When false, nav stays active on nested paths (e.g. /governance/proposals). */
  end?: boolean;
}

interface SvNavLinkProps {
  link: SvNavLinkItem;
}

/** Figma: badge accessory uses gap-1.5 (6px), alert-icon accessory uses gap-2.5 (10px). */
const navLinkSx = (isActive: boolean, accessoryGap: string) => ({
  display: 'inline-flex',
  alignItems: 'center',
  gap: accessoryGap,
  p: NAV_PILL_PX,
  borderRadius: '20px',
  textDecoration: 'none',
  whiteSpace: 'nowrap',
  color: layoutTokens.lightText,
  fontFamily: layoutTokens.fontUi,
  fontSize: '0.875rem',
  fontWeight: 700,
  ...navItemTypography,
  border: '2px solid transparent',
  boxSizing: 'border-box',
  ...(isActive && { borderColor: layoutTokens.navActiveOutline }),
  '&:focus': { outline: 'none' },
  '&:focus-visible': {
    outline: '2px solid',
    outlineColor: layoutTokens.navActiveOutline,
    outlineOffset: '2px',
  },
});

const SvNavLink: React.FC<SvNavLinkProps> = ({ link }) => (
  <NavLink
    id={`navlink-${link.path.replace(/^\//, '')}`}
    data-testid={`navlink-${link.path.replace(/^\//, '')}`}
    to={link.path}
    end={link.end ?? true}
    style={{ textDecoration: 'none' }}
  >
    {({ isActive }) => (
      <Box sx={navLinkSx(isActive, link.hasAlert ? '10px' : '6px')}>
        {link.name}
        {link.badgeCount !== undefined && link.badgeCount > 0 ? (
          <NavCountBadge
            count={link.badgeCount}
            id={`nav-badge-${link.path.replace(/^\//, '')}-count`}
          />
        ) : null}
        {link.hasAlert ? <NavAttentionIcon /> : null}
      </Box>
    )}
  </NavLink>
);

export default SvNavLink;

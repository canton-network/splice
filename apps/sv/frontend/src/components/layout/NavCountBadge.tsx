// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box } from '@mui/material';

import { layoutTokens } from '../../theme/tokens';

interface NavCountBadgeProps {
  count: number;
  id?: string;
}

/** Figma nav notification badge — size-5 bg-red-400 rounded-3xl, text-xs Inter. */
const NavCountBadge: React.FC<NavCountBadgeProps> = ({ count, id }) => {
  if (count <= 0) {
    return null;
  }

  return (
    <Box
      id={id}
      component="span"
      aria-label={`${count} pending`}
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        minWidth: 20,
        height: 20,
        px: count > 9 ? 0.5 : 0,
        borderRadius: '24px',
        bgcolor: layoutTokens.notificationBadge,
        color: 'common.white',
        fontFamily: '"Inter", sans-serif',
        fontSize: '0.75rem',
        fontWeight: 400,
        lineHeight: 1,
      }}
    >
      {count}
    </Box>
  );
};

export default NavCountBadge;

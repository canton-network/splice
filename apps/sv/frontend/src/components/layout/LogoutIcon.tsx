// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box } from '@mui/material';

/**
 * Figma logout glyph — door bracket (open right) with an arrow exiting through it.
 * Path data reconstructed from the true Figma vector export (CF-design-system/svgs),
 * not the lossy Tailwind HTML export — the div-based export flattens this icon into
 * two filled bars that don't resemble a logout glyph at all.
 */
const LogoutIcon: React.FC = () => (
  <Box
    component="span"
    aria-hidden
    sx={{ display: 'inline-flex', width: 16, height: 16, flexShrink: 0 }}
  >
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path d="M6.76 2.25H2.6V13.74H6.76" stroke="white" strokeWidth="2" strokeLinejoin="round" />
      <path d="M5.93 7.99H12.59" stroke="white" strokeWidth="2" strokeLinecap="round" />
      <path
        d="M10.93 5.53L13.42 7.99L10.93 10.46"
        stroke="white"
        strokeWidth="2"
        strokeLinejoin="round"
        strokeLinecap="round"
      />
    </svg>
  </Box>
);

export default LogoutIcon;

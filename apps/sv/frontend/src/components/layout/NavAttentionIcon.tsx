// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Box } from '@mui/material';

/** Figma nav warning icon for Delegate Election (yellow triangle + exclamation cutout). */
const NavAttentionIcon: React.FC = () => (
  <Box
    component="span"
    aria-hidden
    sx={{ display: 'inline-flex', width: 16, height: 16, flexShrink: 0 }}
  >
    <svg
      width="16"
      height="16"
      viewBox="1724.31 414.44 12.86 11.56"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <path
        fillRule="evenodd"
        clipRule="evenodd"
        d="M1725.98 426H1736.02C1737.05 426 1737.69 424.887 1737.17 424L1732.15 415.327C1731.64 414.44 1730.36 414.44 1729.85 415.327L1724.83 424C1724.31 424.887 1724.95 426 1725.98 426ZM1731 421.333C1730.63 421.333 1730.33 421.033 1730.33 420.667V419.333C1730.33 418.967 1730.63 418.667 1731 418.667C1731.37 418.667 1731.67 418.967 1731.67 419.333V420.667C1731.67 421.033 1731.37 421.333 1731 421.333ZM1731.67 424H1730.33V422.667H1731.67V424Z"
        fill="#F3FF97"
      />
    </svg>
  </Box>
);

export default NavAttentionIcon;

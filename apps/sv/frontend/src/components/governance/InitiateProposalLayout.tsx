// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box } from '@mui/material';
import React from 'react';
import { CONTENT_MAX_WIDTH } from '../../theme/tokens';

export interface InitiateProposalLayoutProps {
  children: React.ReactNode;
}

export const InitiateProposalLayout: React.FC<InitiateProposalLayoutProps> = ({ children }) => (
  <Box
    data-testid="initiate-proposal-layout"
    sx={{
      maxWidth: CONTENT_MAX_WIDTH,
      mx: 'auto',
      py: 4,
      px: { xs: 2, sm: 3, md: 4 },
    }}
  >
    {children}
  </Box>
);

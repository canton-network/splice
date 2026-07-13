// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ArrowBack } from '@mui/icons-material';
import { Box, Link, Typography } from '@mui/material';
import React from 'react';
import { Link as RouterLink } from 'react-router';

export interface InitiateProposalHeaderProps {
  actionName: string;
  isReviewStep?: boolean;
}

export const InitiateProposalHeader: React.FC<InitiateProposalHeaderProps> = ({
  actionName,
  isReviewStep = false,
}) => (
  <Box
    data-testid="initiate-proposal-header"
    sx={{ mb: 4, display: 'flex', flexDirection: 'column', gap: 1 }}
  >
    <Link
      component={RouterLink}
      to="/governance/proposals"
      underline="hover"
      color="secondary"
      sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, width: 'fit-content' }}
      data-testid="initiate-proposal-back-link"
    >
      <ArrowBack sx={{ fontSize: 16 }} />
      Governance
    </Link>

    <Typography
      variant="h4"
      component="h1"
      data-testid="initiate-proposal-action-name"
      sx={{ fontFamily: "'Lato', sans-serif", fontWeight: 400, lineHeight: '28px' }}
    >
      {actionName}
    </Typography>

    {isReviewStep && (
      <Typography variant="body2" color="text.secondary" data-testid="initiate-proposal-step-label">
        Review your proposal before submitting
      </Typography>
    )}
  </Box>
);

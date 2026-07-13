// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Typography } from '@mui/material';
import React from 'react';

export interface InitiateProposalHeaderProps {
  actionName: string;
  isReviewStep?: boolean;
}

export const InitiateProposalHeader: React.FC<InitiateProposalHeaderProps> = ({
  actionName,
  isReviewStep = false,
}) => {
  if (!isReviewStep) {
    return null;
  }

  return (
    <Box
      data-testid="initiate-proposal-header"
      sx={{ mb: 4, display: 'flex', flexDirection: 'column', gap: 1 }}
    >
      <Typography
        variant="h4"
        component="h1"
        data-testid="initiate-proposal-action-name"
        sx={{ fontFamily: "'Lato', sans-serif", fontWeight: 400, lineHeight: '28px' }}
      >
        {actionName}
      </Typography>
      <Typography variant="body2" color="text.secondary" data-testid="initiate-proposal-step-label">
        Review your proposal before submitting
      </Typography>
    </Box>
  );
};

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Typography } from '@mui/material';
import React from 'react';
import { CREATE_PROPOSAL_FIELD_HELPER_SX } from '../../constants/createProposalLayout';

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
        sx={{ fontWeight: 400, lineHeight: '28px' }}
      >
        {actionName}
      </Typography>
      <Typography
        component="p"
        data-testid="initiate-proposal-step-label"
        sx={CREATE_PROPOSAL_FIELD_HELPER_SX}
      >
        Review your proposal before submitting
      </Typography>
    </Box>
  );
};

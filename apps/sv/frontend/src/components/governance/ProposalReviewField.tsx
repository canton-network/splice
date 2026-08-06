// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Typography } from '@mui/material';
import React from 'react';
import {
  CREATE_PROPOSAL_FIELD_BODY_SX,
  CREATE_PROPOSAL_FIELD_HELPER_SX,
  CREATE_PROPOSAL_FIELD_LABEL_SX,
  CREATE_PROPOSAL_FIELD_SURFACE_BG,
} from '../../constants/createProposalLayout';

export interface ProposalReviewFieldProps {
  id: string;
  label: string;
  value: React.ReactNode;
  subtitle?: string;
}

export const ProposalReviewField: React.FC<ProposalReviewFieldProps> = ({
  id,
  label,
  value,
  subtitle,
}) => (
  <Box data-testid={`${id}-review-field`}>
    <Typography
      component="p"
      data-testid={`${id}-title`}
      sx={{ ...CREATE_PROPOSAL_FIELD_LABEL_SX, mb: 1 }}
    >
      {label}
    </Typography>

    {subtitle && (
      <Typography
        component="p"
        data-testid={`${id}-subtitle`}
        sx={{ ...CREATE_PROPOSAL_FIELD_HELPER_SX, mb: 1 }}
      >
        {subtitle}
      </Typography>
    )}

    <Box
      sx={{
        bgcolor: CREATE_PROPOSAL_FIELD_SURFACE_BG,
        borderRadius: '4px',
        px: '16px',
        py: '13px',
        minHeight: '48px',
        display: 'flex',
        alignItems: 'center',
      }}
    >
      {typeof value === 'string' ? (
        <Typography
          component="p"
          data-testid={`${id}-field`}
          sx={{
            ...CREATE_PROPOSAL_FIELD_BODY_SX,
            wordBreak: 'break-word',
            width: '100%',
          }}
        >
          {value}
        </Typography>
      ) : (
        <Box data-testid={`${id}-field`} sx={{ width: '100%' }}>
          {value}
        </Box>
      )}
    </Box>
  </Box>
);

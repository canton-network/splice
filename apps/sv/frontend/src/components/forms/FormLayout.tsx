// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Paper } from '@mui/material';
import { InitiateProposalHeader } from '../governance/InitiateProposalHeader';
import {
  CREATE_PROPOSAL_CARD_BG,
  CREATE_PROPOSAL_CARD_BORDER_RADIUS,
  CREATE_PROPOSAL_CARD_PADDING_Y,
  CREATE_PROPOSAL_FIELD_MAX_WIDTH,
} from '../../constants/createProposalLayout';

export interface FormLayoutProps {
  children: React.ReactNode;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  form: any;
  id: string;
  actionName: string;
  isReviewStep?: boolean;
}

export const FormLayout: React.FC<FormLayoutProps> = props => {
  const { children, form, id, actionName, isReviewStep = false } = props;

  return (
    <Box data-testid={id} id={id}>
      <InitiateProposalHeader actionName={actionName} isReviewStep={isReviewStep} />

      <Paper
        elevation={0}
        sx={{
          bgcolor: CREATE_PROPOSAL_CARD_BG,
          borderRadius: CREATE_PROPOSAL_CARD_BORDER_RADIUS,
          py: CREATE_PROPOSAL_CARD_PADDING_Y,
          px: { xs: 2, sm: 4, md: 6 },
          display: 'flex',
          justifyContent: 'center',
        }}
      >
        <Box sx={{ width: '100%', maxWidth: CREATE_PROPOSAL_FIELD_MAX_WIDTH, minWidth: 0 }}>
          <form
            onSubmit={e => {
              e.preventDefault();
              e.stopPropagation();
              form.handleSubmit();
            }}
          >
            <Box
              sx={{ display: 'flex', flexDirection: 'column', gap: 4, width: '100%', minWidth: 0 }}
            >
              {children}
            </Box>
          </form>
        </Box>
      </Paper>
    </Box>
  );
};

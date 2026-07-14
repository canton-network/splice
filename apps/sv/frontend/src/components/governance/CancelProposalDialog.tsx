// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';
import { Box, Button, Dialog, Typography } from '@mui/material';
import React from 'react';
import {
  CREATE_PROPOSAL_CARD_BG,
  CREATE_PROPOSAL_DISCARD_CTA,
} from '../../constants/createProposalLayout';
import {
  createProposalCancelButtonSx,
  createProposalDiscardButtonSx,
} from '../../constants/formButtonStyles';

export interface CancelProposalDialogProps {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
}

export const CancelProposalDialog: React.FC<CancelProposalDialogProps> = ({
  open,
  onClose,
  onConfirm,
}) => (
  <Dialog
    open={open}
    onClose={onClose}
    aria-labelledby="cancel-proposal-dialog-title"
    data-testid="cancel-proposal-dialog"
    PaperProps={{
      sx: {
        bgcolor: CREATE_PROPOSAL_CARD_BG,
        borderRadius: '4px',
        maxWidth: 568,
        width: '100%',
        p: '40px',
      },
    }}
  >
    <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '60px' }}>
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '28px' }}>
        <ErrorOutlineIcon sx={{ fontSize: '32px', color: CREATE_PROPOSAL_DISCARD_CTA }} />

        <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '28px' }}>
          <Typography
            id="cancel-proposal-dialog-title"
            component="p"
            sx={{
              fontFamily: "'Inter', sans-serif",
              fontWeight: 700,
              fontSize: '20px',
              lineHeight: '28px',
              color: '#FFFFFF',
              textAlign: 'center',
            }}
          >
            Are you sure you want to cancel this form?
          </Typography>

          <Typography
            component="p"
            sx={{
              fontFamily: "'Inter', sans-serif",
              fontWeight: 400,
              fontSize: '16px',
              lineHeight: 'normal',
              color: '#FFFFFF',
              textAlign: 'center',
            }}
          >
            Any information you have entered on this vote will be lost and cannot be recovered.
          </Typography>
        </Box>
      </Box>

      <Box sx={{ display: 'flex', justifyContent: 'space-between', width: '100%' }}>
        <Button
          onClick={onClose}
          data-testid="cancel-proposal-dialog-stay-button"
          sx={createProposalCancelButtonSx}
        >
          Cancel
        </Button>
        <Button
          onClick={onConfirm}
          data-testid="cancel-proposal-dialog-confirm-button"
          sx={createProposalDiscardButtonSx}
        >
          Discard &amp; Exit
        </Button>
      </Box>
    </Box>
  </Dialog>
);

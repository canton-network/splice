// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Typography,
} from '@mui/material';
import React from 'react';
import { CREATE_PROPOSAL_CARD_BG } from '../../constants/createProposalLayout';

export interface CancelProposalDialogProps {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
}

const pillButtonSx = {
  height: '39px',
  px: '16px',
  py: '10px',
};

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
      },
    }}
  >
    <DialogTitle
      id="cancel-proposal-dialog-title"
      sx={{ textAlign: 'center', pt: 5, px: 5, pb: 0 }}
    >
      <Typography variant="h6" component="p" sx={{ fontWeight: 600 }}>
        Are you sure you want to cancel this form?
      </Typography>
    </DialogTitle>

    <DialogContent sx={{ textAlign: 'center', px: 5, pt: 2, pb: 0 }}>
      <Typography variant="body2" color="text.secondary">
        Any information you have entered on this vote will be lost and cannot be recovered.
      </Typography>
    </DialogContent>

    <DialogActions sx={{ justifyContent: 'space-between', px: 5, py: 4 }}>
      <Button
        variant="outlined"
        onClick={onClose}
        data-testid="cancel-proposal-dialog-stay-button"
        sx={pillButtonSx}
      >
        Stay
      </Button>
      <Button
        variant="outlined"
        color="warning"
        onClick={onConfirm}
        data-testid="cancel-proposal-dialog-confirm-button"
        sx={pillButtonSx}
      >
        Cancel Form
      </Button>
    </DialogActions>
  </Dialog>
);

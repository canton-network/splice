// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Button } from '@mui/material';
import { useState } from 'react';
import { useFormContext } from '../../hooks/formContext';
import { useNavigate } from 'react-router';
import { CancelProposalDialog } from '../governance/CancelProposalDialog';

export interface FormControlsProps {
  showConfirmation?: boolean;
  onEdit: () => void;
}

const pillButtonSx = {
  height: '39px',
  px: '16px',
  py: '10px',
};

const cancelButtonSx = {
  ...pillButtonSx,
  bgcolor: 'transparent',
  '&:hover': { bgcolor: 'transparent' },
};

export const FormControls: React.FC<FormControlsProps> = props => {
  const { showConfirmation, onEdit } = props;
  const form = useFormContext();
  const navigate = useNavigate();
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);

  const submitTitle = showConfirmation ? 'Submit Proposal' : 'Review Proposal';
  const cancelTitle = showConfirmation ? 'Edit Proposal' : 'Cancel';

  const handleCancel = () => {
    if (showConfirmation) {
      onEdit();
      return;
    }
    setCancelDialogOpen(true);
  };

  const handleConfirmCancel = () => {
    setCancelDialogOpen(false);
    navigate('/governance/proposals');
  };

  return (
    <>
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          gap: '14px',
          pt: 2,
        }}
        data-testid="form-controls"
      >
        <Button
          variant="outlined"
          data-testid="cancel-button"
          onClick={handleCancel}
          type="button"
          sx={cancelButtonSx}
        >
          {cancelTitle}
        </Button>

        <form.Subscribe
          selector={state => [state.canSubmit, state.isSubmitting]}
          children={([canSubmit, isSubmitting]) => (
            <Button
              variant="pill"
              type="submit"
              disabled={!canSubmit || isSubmitting}
              id="submit-button"
              data-testid="submit-button"
              sx={{
                ...pillButtonSx,
                '&:disabled': {
                  bgcolor: '#78716C',
                  color: '#363636',
                  border: 'none',
                },
              }}
            >
              {isSubmitting ? 'Submitting' : submitTitle}
            </Button>
          )}
        />
      </Box>

      <CancelProposalDialog
        open={cancelDialogOpen}
        onClose={() => setCancelDialogOpen(false)}
        onConfirm={handleConfirmCancel}
      />
    </>
  );
};

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DisableConditionally,
  SvClientProvider,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { useMutation } from '@tanstack/react-query';
import React, { useState } from 'react';
import { Button, Stack, TextField, Typography } from '@mui/material';
import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { useSvConfig } from '../utils';

const VALID_PARTY_ID_REGEX = /^.+::[a-zA-Z0-9]+$/;
const VALID_PARTICIPANT_ID_REGEX = /^.+::[a-zA-Z0-9]+$/;

const ValidatorPermissioning: React.FC = () => {
  const { grantValidatorPermission } = useSvAdminClient();

  const [validatorPartyId, setValidatorPartyId] = useState('');
  const [validatorParticipantId, setValidatorParticipantId] = useState('');

  const permissionMutation = useMutation({
    mutationFn: () => {
      return grantValidatorPermission(validatorPartyId, validatorParticipantId);
    },
    onSuccess: () => {
      setValidatorPartyId('');
      setValidatorParticipantId('');
    },
  });

  return (
    <Stack spacing={4} direction="column" justifyContent="center">
      <Typography mt={4} variant="h4">
        Validator Permission
      </Typography>

      {permissionMutation.isError && (
        <Typography variant="body1">
          Error: Something went wrong while granting validator permission.
        </Typography>
      )}

      <Stack direction="column" spacing={1}>
        <Typography variant="h6">Validator Party ID</Typography>
        <TextField
          error={validatorPartyId === '' || !VALID_PARTY_ID_REGEX.test(validatorPartyId)}
          autoComplete="off"
          id="validator-party-id"
          placeholder="e.g., validator::12345abcdef"
          inputProps={{ 'data-testid': 'validator-party-id' }}
          onChange={e => setValidatorPartyId(e.target.value)}
          value={validatorPartyId}
        />
      </Stack>

      <Stack direction="column" mb={4} spacing={1}>
        <Typography variant="h6">Validator Participant ID</Typography>
        <TextField
          error={
            validatorParticipantId === '' ||
            !VALID_PARTICIPANT_ID_REGEX.test(validatorParticipantId)
          }
          autoComplete="off"
          id="validator-participant-id"
          placeholder="e.g., PAR::participant::12345abcdef"
          inputProps={{ 'data-testid': 'validator-participant-id' }}
          onChange={e => setValidatorParticipantId(e.target.value)}
          value={validatorParticipantId}
        />
      </Stack>

      <DisableConditionally
        conditions={[
          { disabled: permissionMutation.isPending, reason: 'Loading...' },
          { disabled: validatorPartyId === '', reason: 'Party ID is required' },
          { disabled: validatorParticipantId === '', reason: 'Participant ID is required' },
          {
            disabled: !VALID_PARTY_ID_REGEX.test(validatorPartyId),
            reason: 'Party ID format is invalid',
            severity: 'warning',
          },
          {
            disabled: !VALID_PARTICIPANT_ID_REGEX.test(validatorParticipantId),
            reason: 'Participant ID format is invalid',
            severity: 'warning',
          },
        ]}
      >
        <Button
          id="grant-validator-permission-btn"
          data-testid="grant-validator-permission-btn"
          variant="pill"
          fullWidth
          size="large"
          onClick={() => {
            permissionMutation.mutate();
          }}
        >
          Grant Permission
        </Button>
      </DisableConditionally>
    </Stack>
  );
};

const ValidatorPermissioningWithContexts: React.FC = () => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <ValidatorPermissioning />
    </SvClientProvider>
  );
};

export default ValidatorPermissioningWithContexts;

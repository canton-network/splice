// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box } from '@mui/material';
import { MemberIdentifier } from '../ui';
import { IDENTIFIER_COMPACT_MAX_WIDTH_PX } from '../ui/identifierStyles';
import { ProposalReviewField } from '../governance/ProposalReviewField';
import type { ResolvedSynchronizerRegistration } from '../../hooks/useSynchronizerRegistrationPicker';
import { registrationFields } from './registrationFields';

interface ResolvedSynchronizerRegistrationProps {
  id: string;
  registration: ResolvedSynchronizerRegistration;
  showWeights?: boolean;
}

const PartyValue: React.FC<{ partyId: string; testId: string }> = ({ partyId, testId }) => (
  <MemberIdentifier
    partyId={partyId}
    isYou={false}
    size="small"
    maxWidth={IDENTIFIER_COMPACT_MAX_WIDTH_PX}
    data-testid={testId}
  />
);

export const ResolvedSynchronizerRegistrationFields: React.FC<
  ResolvedSynchronizerRegistrationProps
> = ({ id, registration, showWeights = false }) => (
  <Box data-testid={id} sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
    <ProposalReviewField
      id={`${id}-synchronizer-id`}
      label="Synchronizer ID"
      value={registration.synchronizerId}
    />
    <ProposalReviewField
      id={`${id}-paymaster`}
      label={registrationFields.paymaster.label}
      value={
        <PartyValue partyId={registration.fields.paymaster} testId={`${id}-paymaster-party-id`} />
      }
    />
    <ProposalReviewField
      id={`${id}-contract-id`}
      label="Registration Contract ID"
      value={registration.contractId}
    />
    {showWeights &&
      (['maxActivityWeight', 'maxAssetActivityWeight'] as const).map(key => (
        <ProposalReviewField
          key={key}
          id={`${id}-current-${key}`}
          label={`Current ${registrationFields[key].label}`}
          value={registrationFields[key].toForm(registration.fields[key])}
        />
      ))}
  </Box>
);

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Alert, Box, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { Contract } from '@canton-network/splice-common-frontend-utils';
import { RegisteredSynchronizer } from '@daml.js/splice-amulet/lib/Splice/DecentralizedSynchronizer';
import { useSvAdminClient } from '../../../contexts/SvAdminServiceContext';
import type { RegisterSynchronizerProposal } from '../../../utils/types';
import {
  RegistrationFields,
  registrationChanges,
  registrationFields,
} from '../../forms/registrationFields';
import { CopyableIdentifier, MemberIdentifier } from '../../ui';
import { ConfigValuesChanges } from '../ConfigValuesChanges';
import { DetailItem } from './DetailItem';

const useRegistrationByContractId = (contractId: string) => {
  const svAdminClient = useSvAdminClient();
  return useQuery({
    queryKey: ['synchronizerRegistrationByContractId', contractId],
    queryFn: async () => {
      const response = await svAdminClient.lookupSynchronizerRegistrationByContractId(contractId);
      return response.registration
        ? Contract.decodeOpenAPI(response.registration, RegisteredSynchronizer).payload
        : null;
    },
  });
};

const SynchronizerIdItem = ({
  idPrefix,
  synchronizerId,
}: {
  idPrefix: string;
  synchronizerId: string;
}) => (
  <DetailItem
    label="Synchronizer ID"
    value={
      <Typography variant="body1" data-testid={`${idPrefix}-synchronizer-id`}>
        {synchronizerId}
      </Typography>
    }
  />
);

const PartyItem = ({
  idPrefix,
  field,
  partyId,
}: {
  idPrefix: string;
  field: 'paymaster';
  partyId: string;
}) => (
  <DetailItem
    label={registrationFields[field].label}
    value={
      <MemberIdentifier
        partyId={partyId}
        isYou={false}
        size="large"
        fullWidth
        data-testid={`${idPrefix}-${field}-party-id`}
      />
    }
  />
);

const WeightItem = ({
  idPrefix,
  field,
  weight,
}: {
  idPrefix: string;
  field: 'maxActivityWeight' | 'maxAssetActivityWeight';
  weight: string;
}) => (
  <DetailItem
    label={registrationFields[field].label}
    value={
      <Typography variant="body1" data-testid={`${idPrefix}-${field}`}>
        {registrationFields[field].toForm(weight)}
      </Typography>
    }
  />
);

interface RegisterSynchronizerSectionProps {
  proposal: RegisterSynchronizerProposal;
}

export const RegisterSynchronizerSection: React.FC<RegisterSynchronizerSectionProps> = ({
  proposal,
}) => {
  const idPrefix = 'proposal-details';
  return (
    <Box
      id="proposal-details-register-synchronizer-section"
      data-testid="proposal-details-register-synchronizer-section"
    >
      <SynchronizerIdItem idPrefix={idPrefix} synchronizerId={proposal.synchronizerId} />
      <PartyItem idPrefix={idPrefix} field="paymaster" partyId={proposal.paymaster} />
      <WeightItem
        idPrefix={idPrefix}
        field="maxActivityWeight"
        weight={proposal.maxActivityWeight}
      />
      <WeightItem
        idPrefix={idPrefix}
        field="maxAssetActivityWeight"
        weight={proposal.maxAssetActivityWeight}
      />
    </Box>
  );
};

interface PinnedRegistrationProps {
  idPrefix: string;
  registeredSynchronizerCid: string;
  isOpen: boolean;
  registration: RegisteredSynchronizer | null | undefined;
  isLoaded: boolean;
}

const PinnedRegistration = ({
  idPrefix,
  registeredSynchronizerCid,
  isOpen,
  registration,
  isLoaded,
}: PinnedRegistrationProps) => (
  <>
    {isOpen && isLoaded && !registration && (
      <Alert severity="warning" variant="outlined" data-testid={`${idPrefix}-stale-warning`}>
        The registration this vote targets is no longer active: the synchronizer was offboarded, or
        its registration was updated, which recreates it under a new contract id. Executing this
        vote will fail.
      </Alert>
    )}
    {registration && (
      <>
        <SynchronizerIdItem idPrefix={idPrefix} synchronizerId={registration.synchronizerId} />
        <PartyItem idPrefix={idPrefix} field="paymaster" partyId={registration.paymaster} />
      </>
    )}
    <DetailItem
      label="Registration Contract ID"
      value={
        <CopyableIdentifier
          value={registeredSynchronizerCid}
          size="large"
          data-testid={`${idPrefix}-contract-id`}
        />
      }
    />
  </>
);

interface ArchiveSynchronizerRegistrationSectionProps {
  registeredSynchronizerCid: string;
  isOpen: boolean;
}

export const ArchiveSynchronizerRegistrationSection: React.FC<
  ArchiveSynchronizerRegistrationSectionProps
> = ({ registeredSynchronizerCid, isOpen }) => {
  const idPrefix = 'proposal-details-archive-synchronizer';
  const registrationQuery = useRegistrationByContractId(registeredSynchronizerCid);
  return (
    <Box id={`${idPrefix}-section`} data-testid={`${idPrefix}-section`}>
      <PinnedRegistration
        idPrefix={idPrefix}
        registeredSynchronizerCid={registeredSynchronizerCid}
        isOpen={isOpen}
        registration={registrationQuery.data}
        isLoaded={registrationQuery.isSuccess}
      />
    </Box>
  );
};

interface UpdateSynchronizerRegistrationSectionProps {
  registeredSynchronizerCid: string;
  newFields: RegistrationFields;
  isOpen: boolean;
}

export const UpdateSynchronizerRegistrationSection: React.FC<
  UpdateSynchronizerRegistrationSectionProps
> = ({ registeredSynchronizerCid, newFields, isOpen }) => {
  const idPrefix = 'proposal-details-update-synchronizer-registration';
  const registrationQuery = useRegistrationByContractId(registeredSynchronizerCid);
  return (
    <Box id={`${idPrefix}-section`} data-testid={`${idPrefix}-section`}>
      <PinnedRegistration
        idPrefix={idPrefix}
        registeredSynchronizerCid={registeredSynchronizerCid}
        isOpen={isOpen}
        registration={registrationQuery.data}
        isLoaded={registrationQuery.isSuccess}
      />
      <DetailItem
        label="Proposed Changes"
        value={
          <ConfigValuesChanges
            changes={registrationChanges(registrationQuery.data ?? undefined, newFields)}
          />
        }
      />
    </Box>
  );
};

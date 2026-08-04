// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useSearchParams } from 'react-router';
import { Loading } from '@canton-network/splice-common-frontend';
import { CreateUnallocatedUnclaimedActivityRecordForm } from '../components/forms/CreateUnallocatedUnclaimedActivityRecordForm';
import { GrantRevokeFeaturedAppForm } from '../components/forms/GrantRevokeFeaturedAppForm';
import { OffboardSvForm } from '../components/forms/OffboardSvForm';
import { SelectAction } from '../components/forms/SelectAction';
import { SetAmuletConfigRulesForm } from '../components/forms/SetAmuletConfigRulesForm';
import { SetDsoConfigRulesForm } from '../components/forms/SetDsoConfigRulesForm';
import { UpdateSvRewardWeightForm } from '../components/forms/UpdateSvRewardWeightForm';
import { useDsoInfos } from '../contexts/SvContext';
import { createProposalActions } from '../utils/governance';
import type { SupportedActionTag } from '../utils/types';
import { Box } from '@mui/material';
import { UpdateFeaturedAppForm } from '../components/forms/UpdateFeaturedAppForm';

const ProposalForm: React.FC<{ action: SupportedActionTag }> = ({ action }) => {
  const dsoInfosQuery = useDsoInfos();
  if (dsoInfosQuery.isPending) {
    return <Loading />;
  }
  switch (action) {
    case 'SRARC_UpdateSvRewardWeight':
      return <UpdateSvRewardWeightForm />;
    case 'SRARC_OffboardSv':
      return <OffboardSvForm />;
    case 'SRARC_GrantFeaturedAppRight':
      return <GrantRevokeFeaturedAppForm selectedAction={'SRARC_GrantFeaturedAppRight'} />;
    case 'SRARC_RevokeFeaturedAppRight':
      return <GrantRevokeFeaturedAppForm selectedAction={'SRARC_RevokeFeaturedAppRight'} />;
    case 'SRARC_CreateUnallocatedUnclaimedActivityRecord':
      return <CreateUnallocatedUnclaimedActivityRecordForm />;
    case 'SRARC_SetConfig':
      return <SetDsoConfigRulesForm />;
    case 'CRARC_SetConfig':
      return <SetAmuletConfigRulesForm />;
    case 'SRARC_UpdateFeaturedAppRight':
      return <UpdateFeaturedAppForm />;
  }
};

const CREATE_PROPOSAL_MAX_WIDTH = 1583;

export const CreateProposal: React.FC = () => {
  const [searchParams, _] = useSearchParams();
  const action = searchParams.get('action');
  const selectedAction = createProposalActions.find(a => a.value === action);

  return (
    <Box sx={{ maxWidth: CREATE_PROPOSAL_MAX_WIDTH, mx: 'auto', p: 4 }}>
      {selectedAction ? (
        <ProposalForm action={selectedAction.value as SupportedActionTag} />
      ) : (
        <SelectAction />
      )}
    </Box>
  );
};

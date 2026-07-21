// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading } from '@canton-network/splice-common-frontend';
import React, { useState } from 'react';

import { FormControl, Stack, TextField, Typography } from '@mui/material';

import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { useDsoInfos } from '../../../contexts/SvContext';
import { activityWeightToOptional } from '../../../utils/governance';

const GrantFeaturedAppRight: React.FC<{
  chooseAction: (action: ActionRequiringConfirmation) => void;
}> = ({ chooseAction }) => {
  const dsoInfosQuery = useDsoInfos();
  const [provider, setProvider] = useState<string>('');
  const [activityWeight, setActivityWeight] = useState<string>('');

  if (dsoInfosQuery.isLoading) {
    return <Loading />;
  }

  if (dsoInfosQuery.isError) {
    return <p>Error: {JSON.stringify(dsoInfosQuery.error)}</p>;
  }

  function chooseGrantAction(provider: string, activityWeight: string) {
    chooseAction({
      tag: 'ARC_DsoRules',
      value: {
        dsoAction: {
          tag: 'SRARC_GrantFeaturedAppRight',
          value: { provider: provider, activityWeight: activityWeightToOptional(activityWeight) },
        },
      },
    });
  }

  function setProviderAction(provider: string) {
    setProvider(provider);
    chooseGrantAction(provider, activityWeight);
  }

  function setActivityWeightAction(activityWeight: string) {
    setActivityWeight(activityWeight);
    chooseGrantAction(provider, activityWeight);
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Provider</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <TextField
          id="set-application-provider"
          onChange={e => setProviderAction(e.target.value)}
          value={provider}
        />
      </FormControl>
      <Typography variant="h6">Activity Weight</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <TextField
          id="set-application-activity-weight"
          onChange={e => setActivityWeightAction(e.target.value)}
          value={activityWeight}
        />
      </FormControl>
    </Stack>
  );
};

export default GrantFeaturedAppRight;

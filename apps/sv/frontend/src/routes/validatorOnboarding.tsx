// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Box, Divider } from '@mui/material';

import ValidatorLicenses from '../components/ValidatorLicenses';
import ValidatorOnboardingSecrets from '../components/ValidatorOnboardingSecrets';
import ValidatorPermissioning from '../components/ValidatorPermissioning';
import { useSvConfig } from '../utils';

const ValidatorOnboarding: React.FC = () => {
  return (
    <Box sx={{ p: 4 }}>
      {useSvConfig().permissioned ? <ValidatorPermissioning /> : <ValidatorOnboardingSecrets />}
      <Divider sx={{ position: 'absolute', left: 0, width: '100vw' }} />
      <ValidatorLicenses />
    </Box>
  );
};

export default ValidatorOnboarding;

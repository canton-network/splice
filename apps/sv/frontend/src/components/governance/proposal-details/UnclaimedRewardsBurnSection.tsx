// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box } from '@mui/material';
import type { UnclaimedRewardsBurnProposal } from '../../../utils/types';
import { DetailItem } from './DetailItem';

export const UnclaimedRewardsBurnSection: React.FC<UnclaimedRewardsBurnProposal> = ({ amount }) => (
  <Box
    id="proposal-details-unclaimed-rewards-burn-section"
    data-testid="proposal-details-unclaimed-rewards-burn-section"
    sx={{ display: 'contents' }}
  >
    <DetailItem
      label="Amount"
      value={amount}
      labelId="proposal-details-amount-label"
      valueId="proposal-details-amount-value"
    />
  </Box>
);

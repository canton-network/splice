// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Divider, Stack, Typography } from '@mui/material';

import { CREATE_PROPOSAL_FIELD_LABEL_SX } from '../../../constants/createProposalLayout';

interface DetailItemProps {
  label: string;
  value: React.ReactNode;
  labelId?: string;
  valueId?: string;
}

export const DetailItem: React.FC<DetailItemProps> = props => {
  const { label, value, labelId, valueId } = props;

  return (
    <Stack gap={3}>
      <Typography
        component="p"
        sx={CREATE_PROPOSAL_FIELD_LABEL_SX}
        id={labelId}
        data-testid={labelId}
      >
        {label}
      </Typography>
      {typeof value === 'string' ? (
        <Typography variant="body1" lineHeight={1} fontSize={16} id={valueId} data-testid={valueId}>
          {value}
        </Typography>
      ) : (
        value
      )}
      <Divider sx={{ borderBottomWidth: 2 }} />
    </Stack>
  );
};

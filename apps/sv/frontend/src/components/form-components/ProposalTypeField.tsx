// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Typography } from '@mui/material';
import {
  CREATE_PROPOSAL_FIELD_BODY_SX,
  CREATE_PROPOSAL_FIELD_LABEL_SX,
} from '../../constants/createProposalLayout';
import { useFieldContext } from '../../hooks/formContext';

export interface ProposalTypeFieldProps {
  id: string;
  title?: string;
}

export const ProposalTypeField: React.FC<ProposalTypeFieldProps> = props => {
  const { id, title = 'Proposal type' } = props;
  const field = useFieldContext<string>();

  return (
    <Box>
      <Typography
        component="p"
        id={`${id}-title`}
        data-testid={`${id}-title`}
        sx={{ ...CREATE_PROPOSAL_FIELD_LABEL_SX, mb: 1 }}
      >
        {title}
      </Typography>

      <Typography component="p" id={id} data-testid={id} sx={CREATE_PROPOSAL_FIELD_BODY_SX}>
        {field.state.value}
      </Typography>
    </Box>
  );
};

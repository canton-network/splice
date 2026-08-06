// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Typography } from '@mui/material';
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
      <Typography variant="h6" id={`${id}-title`} data-testid={`${id}-title`} gutterBottom>
        {title}
      </Typography>

      <Typography variant="h4" id={id} data-testid={id}>
        {field.state.value}
      </Typography>
    </Box>
  );
};

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, TextField as MuiTextField, Typography } from '@mui/material';
import {
  CREATE_PROPOSAL_FIELD_HELPER_SX,
  CREATE_PROPOSAL_FIELD_LABEL_SX,
} from '../../constants/createProposalLayout';
import { useFieldContext } from '../../hooks/formContext';
import { useDsoInfos } from '../../contexts/SvContext';
import {
  DEFAULT_PROPOSAL_SUMMARY_MAX_LENGTH,
  PROPOSAL_SUMMARY_PLACEHOLDER,
  PROPOSAL_SUMMARY_SUBTITLE,
  PROPOSAL_SUMMARY_TITLE,
} from '../../utils/constants';
import { proposalSummaryFieldSx } from '../../themes/fieldStyles';

export interface ProposalSummaryFieldProps {
  id: string;
  title?: string;
  optional?: boolean;
  subtitle?: string;
}

export const ProposalSummaryField: React.FC<ProposalSummaryFieldProps> = props => {
  const { title, optional, id, subtitle } = props;
  const field = useFieldContext<string>();
  const dsoInfosQuery = useDsoInfos();
  const maxLength = Number(
    dsoInfosQuery.data?.dsoRules.payload.config.maxTextLength ?? DEFAULT_PROPOSAL_SUMMARY_MAX_LENGTH
  );
  const currentLength = field.state.value.length;

  return (
    <Box>
      <Typography component="p" sx={{ ...CREATE_PROPOSAL_FIELD_LABEL_SX, mb: 1 }}>
        {title || PROPOSAL_SUMMARY_TITLE}
        {optional && (
          <Typography component="span" sx={{ ...CREATE_PROPOSAL_FIELD_HELPER_SX, ml: 1 }}>
            optional
          </Typography>
        )}
      </Typography>
      <MuiTextField
        fullWidth
        multiline
        variant="outlined"
        autoComplete="off"
        sx={proposalSummaryFieldSx}
        id={id}
        value={field.state.value}
        onBlur={field.handleBlur}
        onChange={e => field.handleChange(e.target.value)}
        error={!field.state.meta.isValid}
        helperText={field.state.meta.errors?.[0]}
        placeholder={PROPOSAL_SUMMARY_PLACEHOLDER}
        inputProps={{ 'data-testid': id, maxLength }}
      />
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          gap: 2,
        }}
      >
        <Typography
          component="p"
          data-testid={`${id}-subtitle`}
          sx={CREATE_PROPOSAL_FIELD_HELPER_SX}
        >
          {subtitle || PROPOSAL_SUMMARY_SUBTITLE}
        </Typography>
        <Typography
          component="p"
          data-testid={`${id}-character-counter`}
          sx={{ ...CREATE_PROPOSAL_FIELD_HELPER_SX, flexShrink: 0 }}
        >
          {currentLength}/{maxLength}
        </Typography>
      </Box>
    </Box>
  );
};

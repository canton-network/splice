// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, TextField as MuiTextField, Typography } from '@mui/material';
import { useFieldContext } from '../../hooks/formContext';
import { useDsoInfos } from '../../contexts/SvContext';
import {
  DEFAULT_PROPOSAL_SUMMARY_MAX_LENGTH,
  PROPOSAL_SUMMARY_SUBTITLE,
  PROPOSAL_SUMMARY_TITLE,
} from '../../utils/constants';
import {
  fieldDescriptionSx,
  fieldSectionSx,
  fieldSectionTitleSx,
  proposalSummaryFieldSx,
} from '../../themes/fieldStyles';

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
    <Box sx={fieldSectionSx}>
      <Typography sx={fieldSectionTitleSx}>
        {title || PROPOSAL_SUMMARY_TITLE}
        {optional && (
          <Typography
            component="span"
            fontSize={12}
            lineHeight="22px"
            color="text.light"
            sx={{ ml: 1 }}
          >
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
        <Typography sx={fieldDescriptionSx} data-testid={`${id}-subtitle`}>
          {subtitle || PROPOSAL_SUMMARY_SUBTITLE}
        </Typography>
        <Typography
          fontSize={12}
          lineHeight="22px"
          color="text.secondary"
          data-testid={`${id}-character-counter`}
          sx={{ flexShrink: 0 }}
        >
          {currentLength}/{maxLength}
        </Typography>
      </Box>
    </Box>
  );
};

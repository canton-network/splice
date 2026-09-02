// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import {
  Box,
  Button,
  Checkbox,
  FormControlLabel,
  IconButton,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import { DeleteOutline, InfoOutlined } from '@mui/icons-material';
import { withForm } from '../../hooks/form';
import type { SwitchOverEntry } from '../forms/formValidators';
import type { CommonProposalFormData, ConfigFormData } from '../../utils/types';

interface SwitchOverCapableFormData {
  common: CommonProposalFormData;
  config: ConfigFormData;
  switchOverTimes: { entries: SwitchOverEntry[]; allowNonFutureDated: boolean };
}

const SWITCH_OVER_RULES =
  'Each key must be unique and non-empty. Each switch-over time must be at least 1 day after the ' +
  "Effective Date, unless 'Allow non-future-dated switch-over times' is enabled or the proposal " +
  'takes effect at threshold.';

export const SwitchOverTimesField = withForm({
  defaultValues: {} as SwitchOverCapableFormData, // type carrier only
  props: {
    effectiveDate: undefined as string | undefined,
    title: '',
  },
  render: ({ form, effectiveDate, title }) => {
    const allowNonFutureDated = form.state.values.switchOverTimes.allowNonFutureDated;
    const rowFloor = allowNonFutureDated ? null : dayjs(effectiveDate).add(1, 'day');
    const defaultNewTime = () => dayjs(effectiveDate).add(1, 'day').format(dateTimeFormatISO);

    return (
      <Stack spacing={2}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
          <Typography component="p">{title}</Typography>
          <Tooltip title={SWITCH_OVER_RULES}>
            <InfoOutlined
              fontSize="small"
              color="action"
              data-testid="switchover-rules-info"
              aria-label={SWITCH_OVER_RULES}
            />
          </Tooltip>
        </Box>

        <form.Field name="switchOverTimes.entries" mode="array">
          {arrayField => (
            <Stack spacing={1}>
              {arrayField.state.value.map((_, i) => (
                <Box key={i} sx={{ display: 'flex', gap: 2, alignItems: 'flex-start' }}>
                  <form.AppField name={`switchOverTimes.entries[${i}].key`}>
                    {field => (
                      <field.TextField
                        id={`switchover-key-${i}`}
                        title="Key"
                        muiTextFieldProps={{ placeholder: 'e.g. amulet-v2' }}
                      />
                    )}
                  </form.AppField>

                  <form.AppField name={`switchOverTimes.entries[${i}].time`}>
                    {field => (
                      <field.DateField
                        id={`switchover-time-${i}`}
                        title="Switch-over time"
                        minDate={rowFloor}
                      />
                    )}
                  </form.AppField>

                  <IconButton
                    aria-label="remove switch-over"
                    data-testid={`switchover-remove-${i}`}
                    onClick={() => arrayField.removeValue(i)}
                  >
                    <DeleteOutline />
                  </IconButton>
                </Box>
              ))}

              <Button
                variant="outlined"
                data-testid="switchover-add"
                onClick={() => arrayField.pushValue({ key: '', time: defaultNewTime() })}
              >
                Add switch-over
              </Button>
            </Stack>
          )}
        </form.Field>

        <form.AppField name="switchOverTimes.allowNonFutureDated">
          {field => (
            <FormControlLabel
              control={
                <Checkbox
                  checked={!!field.state.value}
                  onChange={e => field.handleChange(e.target.checked)}
                  data-testid="switchover-allow-non-future"
                />
              }
              label="Allow non-future-dated switch-over times"
            />
          )}
        </form.AppField>
      </Stack>
    );
  },
});

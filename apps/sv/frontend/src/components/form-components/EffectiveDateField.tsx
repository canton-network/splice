// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { KeyboardArrowDown } from '@mui/icons-material';
import { useFieldContext } from '../../hooks/formContext';
import { DesktopDateTimePicker, LocalizationProvider } from '@mui/x-date-pickers';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs';
import { EffectivityType } from '../../utils/types';
import React, { useMemo } from 'react';
import { RadioSelector } from './RadioSelector';

const effectiveAtDisplayFormat = 'YYYY-MM-DD HH:mm';

export interface EffectiveDateFieldProps {
  title?: string;
  description?: string;
  initialEffectiveDate?: string;
  id: string;
}

export const EffectiveDateField: React.FC<EffectiveDateFieldProps> = props => {
  const { initialEffectiveDate, id } = props;
  const title = props.title ?? 'Effective At';
  const dateDescription =
    props.description ?? 'Select the block at which the proposal will take effect';

  const field = useFieldContext<{
    type: EffectivityType;
    effectiveDate: string | undefined;
  }>();

  const currentType = field.state.value?.type || 'custom';

  const dateValue = useMemo(
    () => dayjs(field.state.value.effectiveDate),
    [field.state.value.effectiveDate]
  );

  const handleTypeChange = (type: EffectivityType) => {
    if (type === 'custom') {
      const currentDate = field.state.value?.effectiveDate
        ? dayjs(field.state.value.effectiveDate)
        : dayjs(initialEffectiveDate);

      field.handleChange({
        type: 'custom',
        effectiveDate: currentDate.format(dateTimeFormatISO),
      });
    } else {
      field.handleChange({
        type: 'threshold',
        effectiveDate: undefined,
      });
    }
  };

  return (
    <RadioSelector
      id={id}
      title={title}
      value={currentType}
      onChange={value => handleTypeChange(value as EffectivityType)}
      options={[
        {
          value: 'custom',
          label: 'Date',
          description: dateDescription,
          extension:
            currentType === 'custom' ? (
              <LocalizationProvider dateAdapter={AdapterDayjs}>
                <DesktopDateTimePicker
                  value={dateValue}
                  format={effectiveAtDisplayFormat}
                  ampm={false}
                  sx={{ width: '100%', display: 'block' }}
                  onClose={() => field.handleBlur()}
                  onChange={newDate => {
                    field.handleChange({
                      type: 'custom',
                      effectiveDate: newDate?.format(dateTimeFormatISO) || undefined,
                    });
                  }}
                  enableAccessibleFieldDOMStructure={false}
                  slots={{
                    openPickerIcon: KeyboardArrowDown,
                  }}
                  slotProps={{
                    openPickerButton: {
                      sx: {
                        color: 'text.light',
                        marginRight: 0,
                        p: 0,
                        cursor: 'pointer',
                        '& .MuiSvgIcon-root': {
                          fontSize: 16,
                        },
                      },
                    },
                    textField: {
                      fullWidth: true,
                      variant: 'outlined',
                      id: `${id}-field`,
                      className: 'effective-date-field',
                      error: !field.state.meta.isValid,
                      helperText: field.state.meta.errors?.[0],
                      onBlur: field.handleBlur,
                      inputProps: {
                        'data-testid': `${id}-field`,
                      },
                      sx: theme => ({
                        width: '100%',
                        '& .MuiOutlinedInput-root': {
                          backgroundColor: '#363636',
                          borderRadius: '4px',
                          display: 'flex',
                          justifyContent: 'space-between',
                          alignItems: 'center',
                          alignSelf: 'stretch',
                          flexWrap: 'nowrap',
                          padding: '13px 16px',
                          overflow: 'hidden',
                          minHeight: 'unset',
                          '& fieldset': {
                            border: 'none',
                            borderRadius: '4px',
                          },
                        },
                        '& .MuiOutlinedInput-input': {
                          ...theme.typography.body2,
                          flex: 1,
                          minWidth: 0,
                          padding: 0,
                          lineHeight: '22px',
                          color: theme.palette.text.light,
                          backgroundColor: 'transparent',
                          borderRadius: 0,
                          WebkitBoxShadow: 'none',
                        },
                        '& .MuiInputAdornment-root': {
                          flexShrink: 0,
                          marginLeft: theme.spacing(1.25),
                        },
                        '& .MuiFormHelperText-root': {
                          mx: 0,
                        },
                      }),
                    },
                  }}
                />
              </LocalizationProvider>
            ) : null,
        },
        {
          value: 'threshold',
          label: 'Make effective at threshold',
          description:
            'This will allow the vote proposal to take effect immediately when 2/3 vote in favor',
          radioId: 'effective-at-threshold-radio',
          testId: 'effective-at-threshold-radio',
        },
      ]}
    />
  );
};

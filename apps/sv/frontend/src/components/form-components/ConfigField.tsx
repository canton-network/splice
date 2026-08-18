// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Link as RouterLink } from 'react-router';
import {
  Box,
  Divider,
  FormControl,
  MenuItem,
  Select,
  TextField as MuiTextField,
  Typography,
} from '@mui/material';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { useFieldContext } from '../../hooks/formContext';
import type { ConfigChange, PendingConfigFieldInfo } from '../../utils/types';
import { nextScheduledSynchronizerUpgradeFormat } from '@canton-network/splice-common-frontend-utils';
import { configFieldFieldSx, configFieldInputSx } from '../../themes/fieldStyles';

dayjs.extend(relativeTime);

export interface ConfigFieldProps {
  configChange: ConfigChange;
  effectiveDate?: string | undefined;
  pendingFieldInfo?: PendingConfigFieldInfo;
}

export type ConfigFieldState = {
  fieldName: string;
  value: string;
};

export const ConfigField: React.FC<ConfigFieldProps> = props => {
  const { configChange, effectiveDate, pendingFieldInfo } = props;
  const field = useFieldContext<ConfigFieldState>();

  const isSynchronizerUpgradeTime = [
    'nextScheduledSynchronizerUpgradeTime',
    'nextScheduledLogicalSynchronizerUpgradeTopologyFreezeTime',
    'nextScheduledLogicalSynchronizerUpgradeUpgradeTime',
  ].includes(field.state.value?.fieldName);
  const isSynchronizerUpgradeField =
    field.state.value?.fieldName.startsWith('nextScheduledSynchronizerUpgrade') ||
    field.state.value?.fieldName.startsWith('nextScheduledLogicalSynchronizerUpgrade');

  // We disable the field if it is pending and the value is the default value.
  // The default value check is to handle the case where the user made a change
  // to the field before it became a field with pending changes.
  // This gives them the chance to revert that change.
  const isPendingAndDefaultValue =
    pendingFieldInfo !== undefined && field.state.meta.isDefaultValue;

  const isEffectiveAtThreshold = !effectiveDate;

  // When effective at Threshold, we disable the upgrade time and migrationId config fields
  const isEffectiveAtThresholdAndSyncUpgradeTimeOrMigrationId =
    isEffectiveAtThreshold && (isSynchronizerUpgradeTime || isSynchronizerUpgradeField);

  const isDisabled =
    isPendingAndDefaultValue ||
    isEffectiveAtThresholdAndSyncUpgradeTimeOrMigrationId ||
    configChange.disabled;

  const textFieldProps = {
    variant: 'outlined' as const,
    color: field.state.meta.isDefaultValue ? ('primary' as const) : ('secondary' as const),
    focused: !field.state.meta.isDefaultValue,
    autoComplete: 'off' as const,
    sx: configFieldFieldSx,
    slotProps: {
      input: {
        sx: configFieldInputSx,
      },
    },
    inputProps: {
      'data-testid': `config-field-${configChange.fieldName}`,
    },
    disabled: isDisabled,
  };

  return (
    <>
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          gap: 2,
          minWidth: 0,
        }}
      >
        <Box sx={{ minWidth: 0, flex: 1, pr: 1 }}>
          <Typography variant="body1" data-testid={`config-label-${configChange.fieldName}`}>
            {configChange.label}
          </Typography>
          <Typography
            fontFamily="'Source Code Pro', monospace"
            color="colors.neutral.70"
            sx={{ mt: 1 }}
            data-testid={`config-field-name-${configChange.fieldName}`}
          >
            {configChange.fieldName}
          </Typography>
        </Box>
        <Box sx={{ width: 238, maxWidth: '100%', minWidth: 0, flexShrink: 0 }}>
          {configChange.options ? (
            <FormControl size="small" fullWidth disabled={isDisabled}>
              <Select
                value={field.state.value?.value || ''}
                onBlur={field.handleBlur}
                onChange={e =>
                  field.handleChange({
                    fieldName: configChange.fieldName,
                    value: e.target.value,
                  })
                }
                data-testid={`config-field-${configChange.fieldName}`}
                color={field.state.meta.isDefaultValue ? 'primary' : 'secondary'}
              >
                {configChange.options.map(option => (
                  <MenuItem key={option.value} value={option.value}>
                    {option.label}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          ) : (
            <MuiTextField
              {...textFieldProps}
              fullWidth
              // We choose empty string to represent fields that could be undefined because their values have not been set.
              value={field.state.value?.value || ''}
              onBlur={field.handleBlur}
              onChange={e =>
                field.handleChange({
                  fieldName: configChange.fieldName,
                  value: e.target.value,
                })
              }
            />
          )}

          {configChange.description && (
            <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
              {configChange.description}
            </Typography>
          )}

          {!field.state.meta.isDefaultValue && (
            <Typography
              variant="caption"
              color="text.secondary"
              title={configChange.currentValue}
              sx={{
                mt: 0.5,
                display: 'block',
                overflowWrap: 'anywhere',
                wordBreak: 'break-word',
              }}
              data-testid={`config-current-value-${configChange.fieldName}`}
            >
              Current Configuration: {configChange.currentValue}
            </Typography>
          )}

          {isSynchronizerUpgradeTime && (
            <SynchronizerUpgradeTimeDisplay
              fieldName={field.state.value?.fieldName || ''}
              effectiveDate={effectiveDate}
              configChange={configChange}
            />
          )}

          {pendingFieldInfo && <PendingConfigDisplay pendingFieldInfo={pendingFieldInfo} />}
        </Box>
      </Box>
      <Divider />
    </>
  );
};

interface PendingConfigDisplayProps {
  pendingFieldInfo: PendingConfigFieldInfo;
}

export const PendingConfigDisplay: React.FC<PendingConfigDisplayProps> = ({ pendingFieldInfo }) => {
  const { fieldName, pendingValue, proposalCid, effectiveDate } = pendingFieldInfo;
  const effectiveText =
    effectiveDate === 'Threshold' ? 'at Threshold' : dayjs(effectiveDate).fromNow();

  return (
    <Box
      sx={{ mt: 0.5, width: '100%', minWidth: 0 }}
      data-testid={`config-pending-value-${fieldName}`}
    >
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ display: 'block', textAlign: 'center' }}
      >
        Pending Configuration:{' '}
        <Box
          component="strong"
          title={pendingValue}
          sx={{
            display: 'inline',
            overflowWrap: 'anywhere',
            wordBreak: 'break-word',
            fontWeight: 700,
          }}
        >
          {pendingValue}
        </Box>
      </Typography>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ display: 'block', textAlign: 'center', mt: 0.5 }}
      >
        This{' '}
        <RouterLink
          to={`/governance/proposals/${proposalCid}`}
          target="_blank"
          rel="noopener noreferrer"
          style={{ color: 'inherit' }}
        >
          pending configuration
        </RouterLink>{' '}
        will go into effect <strong>{effectiveText}</strong>
      </Typography>
    </Box>
  );
};

interface SynchronizerUpgradeTimeDisplayProps {
  effectiveDate: string | undefined;
  fieldName: string;
  configChange: ConfigChange;
}

export const synchronizerUpgradeTimeDefault = (
  fieldName: string,
  effectiveDate: string | undefined
): dayjs.Dayjs => {
  const defaultTime = dayjs(effectiveDate).utc().add(1, 'hour');
  return fieldName == 'nextScheduledLogicalSynchronizerUpgradeUpgradeTime'
    ? defaultTime.add(1, 'day')
    : defaultTime;
};

export const SynchronizerUpgradeTimeDisplay: React.FC<
  SynchronizerUpgradeTimeDisplayProps
> = props => {
  const { effectiveDate, fieldName } = props;
  const defaultMigrationTime = synchronizerUpgradeTimeDefault(fieldName, effectiveDate).format(
    nextScheduledSynchronizerUpgradeFormat
  );

  return (
    <Typography
      variant="caption"
      color="text.secondary"
      sx={{ mt: 0.5, display: 'block', textAlign: 'center' }}
      data-testid={`${fieldName}-default`}
    >
      {`Default: ${defaultMigrationTime}`}
    </Typography>
  );
};

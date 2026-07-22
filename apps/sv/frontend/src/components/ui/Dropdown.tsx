// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  Box,
  FormControl,
  FormHelperText,
  MenuItem,
  Select,
  SelectChangeEvent,
  SxProps,
  Theme,
  Typography,
} from '@mui/material';

/**
 * Source of truth: Figma Dev Mode node `3870:3442` ("Dropdown fields") and
 * [#2652](https://github.com/canton-network/splice/issues/2652) reference.
 * Prop surface mirrors `components.md` Input/Select: label, required,
 * placeholder, helperText, hasDropdown (always true here), state.
 */

export type DropdownState = 'default' | 'disabled' | 'error';

export interface DropdownOption {
  value: string;
  label: string;
  /** Optional per-option test id (defaults to value). */
  testId?: string;
}

export interface DropdownProps {
  options: DropdownOption[];
  value: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
  label?: string;
  required?: boolean;
  placeholder?: string;
  helperText?: string;
  state?: DropdownState;
  id?: string;
  labelId?: string;
  testId?: string;
  disabled?: boolean;
  error?: boolean;
  fullWidth?: boolean;
  renderValue?: (selected: string, options: DropdownOption[]) => React.ReactNode;
  sx?: SxProps<Theme>;
}

/** Dev Mode: `background: var(--grey54, #363636)` */
const FIELD_BG = 'var(--grey54, #363636)';

/** Figma "Body M" on nodes `3870:3442` / `1724:3506`: Inter 14px/400/22px. */
const valueTextSx = {
  fontFamily: "'Inter', sans-serif",
  fontSize: '14px',
  fontWeight: 400,
  lineHeight: '22px',
  color: '#E2E2E2',
  fontFeatureSettings: "'liga' off, 'clig' off",
};

/** Empty-state placeholder node `I3870:3442;120:415`: Inter 14px, grey105. */
const placeholderTextSx = {
  ...valueTextSx,
  color: '#696969',
};

/** Figma "FIELD H": Inter Semi Bold 12px uppercase, grey226. */
const labelSx = {
  fontFamily: "'Inter', sans-serif",
  fontSize: '12px',
  fontWeight: 600,
  lineHeight: '22px',
  textTransform: 'uppercase' as const,
  color: '#E2E2E2',
  mb: '8px',
};

const ChevronDownIcon: React.FC<React.SVGProps<SVGSVGElement>> = props => (
  <svg
    {...props}
    width={16}
    height={16}
    viewBox="0 0 16 16"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
  >
    <path
      d="M4 6l4 4 4-4"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

const resolveLabelId = (
  labelId: string | undefined,
  id: string | undefined,
  label: string | undefined
): string | undefined => labelId ?? (label ? `${id ?? 'dropdown'}-label` : undefined);

export const Dropdown: React.FC<DropdownProps> = ({
  options,
  value,
  onChange,
  onBlur,
  label,
  required = false,
  placeholder,
  helperText,
  state = 'default',
  id,
  labelId: labelIdProp,
  testId,
  disabled: disabledProp,
  error: errorProp,
  fullWidth = true,
  renderValue,
  sx,
}) => {
  const isDisabled = disabledProp ?? state === 'disabled';
  const isError = errorProp ?? state === 'error';
  const resolvedId = id ?? testId ?? 'dropdown';
  const resolvedLabelId = resolveLabelId(labelIdProp, resolvedId, label);
  const showLabel = Boolean(label);

  const defaultRenderValue = (selected: string) => {
    if (!selected) {
      return placeholder ? (
        <Box component="span" sx={placeholderTextSx}>
          {placeholder}
        </Box>
      ) : null;
    }
    const option = options.find(o => o.value === selected);
    return (
      <Box component="span" sx={valueTextSx}>
        {option?.label ?? selected}
      </Box>
    );
  };

  return (
    <FormControl fullWidth={fullWidth} error={isError} sx={sx}>
      {showLabel && (
        <Typography component="label" id={resolvedLabelId} htmlFor={resolvedId} sx={labelSx}>
          {label}
          {required && (
            <Box component="span" sx={{ color: '#E2E2E2', ml: '2px' }} aria-hidden="true">
              *
            </Box>
          )}
        </Typography>
      )}

      <Select
        labelId={resolvedLabelId}
        id={resolvedId}
        data-testid={testId ?? resolvedId}
        displayEmpty={Boolean(placeholder)}
        value={value}
        disabled={isDisabled}
        onChange={(e: SelectChangeEvent) => onChange(e.target.value as string)}
        onBlur={onBlur}
        IconComponent={ChevronDownIcon}
        renderValue={selected =>
          renderValue
            ? renderValue(selected as string, options)
            : defaultRenderValue(selected as string)
        }
        sx={theme => ({
          bgcolor: FIELD_BG,
          borderRadius: `${theme.shape.borderRadius}px`,
          /**
           * Global `MuiInputBase` override paints `.MuiSelect-select` with
           * `neutral[10]` — repeat FIELD_BG on the inner element so it shows.
           */
          '& .MuiSelect-select': {
            bgcolor: FIELD_BG,
            paddingBlock: '13px',
            paddingLeft: '16px',
          },
          '& .MuiSelect-icon': { color: '#E2E2E2' },
          '& .MuiOutlinedInput-notchedOutline': { border: 'none' },
          '&:hover .MuiOutlinedInput-notchedOutline': { border: 'none' },
          '&.Mui-focused .MuiOutlinedInput-notchedOutline': { border: 'none' },
        })}
      >
        {options.map(option => (
          <MenuItem
            key={option.value}
            value={option.value}
            data-testid={option.testId ?? option.value}
            sx={valueTextSx}
          >
            {option.label}
          </MenuItem>
        ))}
      </Select>

      {helperText && (
        <FormHelperText data-testid={`${resolvedId}-helper`}>{helperText}</FormHelperText>
      )}
    </FormControl>
  );
};

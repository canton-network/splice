// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type { SxProps, Theme } from '@mui/material/styles';

const fieldSurfaceBackground = '#363636';

export const fieldSectionTitleSx: SxProps<Theme> = {
  fontSize: 12,
  lineHeight: '22px',
  fontWeight: 600,
  textTransform: 'uppercase',
  color: 'common.white',
};

export const fieldDescriptionSx: SxProps<Theme> = {
  fontSize: 12,
  lineHeight: '22px',
  color: 'text.light',
};

export const fieldSectionSx: SxProps<Theme> = {
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
};

const fieldHelperSx: SxProps<Theme> = {
  '& .MuiFormHelperText-root': {
    mx: 0,
    mt: 1,
  },
};

const inputTypographySx = (theme: Theme) => ({
  fontSize: theme.typography.body2.fontSize,
  fontWeight: theme.typography.body2.fontWeight,
  fontFamily: theme.typography.body2.fontFamily,
  lineHeight: '22px',
  letterSpacing: 0,
  color: theme.palette.text.light,
  backgroundColor: 'transparent',
  boxSizing: 'border-box' as const,
  WebkitBoxShadow: 'none',
});

const fieldOutlineSx = (theme: Theme) => ({
  '& .MuiOutlinedInput-notchedOutline, & fieldset': {
    border: 'none',
    borderRadius: '4px',
  },
  '&.Mui-error .MuiOutlinedInput-notchedOutline, &.Mui-error fieldset': {
    border: `1px solid ${theme.palette.error.main}`,
  },
});

const fieldAdornmentSx = {
  '& .MuiInputAdornment-root': {
    margin: 0,
    maxHeight: '22px',
    height: '22px',
    alignSelf: 'center',
  },
  '& .MuiInputAdornment-positionEnd': {
    marginLeft: 'auto',
  },
  '& .MuiInputAdornment-root .MuiIconButton-root': {
    color: 'common.white',
    padding: 0,
    width: 16,
    height: 16,
    '& svg': {
      fontSize: 16,
    },
  },
  '& .MuiSelect-icon': {
    color: 'common.white',
    fontSize: 16,
    top: 'calc(50% - 0.5em)',
  },
};

const multilineSurfaceSx = (theme: Theme) => ({
  display: 'flex',
  padding: '13px 16px',
  justifyContent: 'space-between',
  alignItems: 'flex-start',
  alignContent: 'flex-start',
  flexWrap: 'wrap',
  rowGap: '10px',
  alignSelf: 'stretch',
  backgroundColor: fieldSurfaceBackground,
  borderRadius: '4px',
  overflow: 'hidden',
  ...fieldOutlineSx(theme),
});

const multilineInputSx = (theme: Theme) => ({
  ...inputTypographySx(theme),
  flex: 1,
  width: '100%',
  minWidth: '100%',
  padding: 0,
});

export const singleLineInputRootSx: SxProps<Theme> = theme => ({
  ...inputTypographySx(theme),
  display: 'flex',
  padding: '13px 16px',
  justifyContent: 'space-between',
  alignItems: 'center',
  alignContent: 'center',
  flexWrap: 'wrap',
  rowGap: '10px',
  alignSelf: 'stretch',
  minHeight: 0,
  height: 'auto',
  backgroundColor: fieldSurfaceBackground,
  borderRadius: '4px',
  overflow: 'hidden',
  ...fieldOutlineSx(theme),
  ...fieldAdornmentSx,
  '&.MuiInputBase-root, &.MuiOutlinedInput-root, &.MuiInputBase-adornedEnd': {
    minHeight: 0,
    height: 'auto',
  },
  // Override common MuiInputBase global `.MuiOutlinedInput-input` background (neutral[10]).
  '& .MuiOutlinedInput-input, & .MuiInputBase-input, & input, & .MuiSelect-select': {
    ...inputTypographySx(theme),
    flex: 1,
    minWidth: 0,
    padding: 0,
    minHeight: 0,
    height: 'auto',
  },
  '&.MuiInputBase-sizeSmall': {
    minHeight: 0,
  },
  '& .MuiOutlinedInput-inputSizeSmall': {
    padding: 0,
  },
});

const proposalSummaryInputRootSx: SxProps<Theme> = theme => ({
  ...multilineSurfaceSx(theme),
  height: '130px',
  '& textarea, & .MuiOutlinedInput-input': {
    ...multilineInputSx(theme),
    alignSelf: 'stretch',
    minHeight: 0,
    height: '100%',
    resize: 'none',
    overflow: 'auto',
  },
});

const configFieldInputRootSx: SxProps<Theme> = theme => ({
  ...inputTypographySx(theme),
  display: 'flex',
  width: '238px',
  maxWidth: '100%',
  height: '48px',
  padding: '14px 24px',
  justifyContent: 'flex-end',
  alignItems: 'center',
  gap: '10px',
  boxSizing: 'border-box',
  minHeight: 0,
  backgroundColor: fieldSurfaceBackground,
  borderRadius: '4px',
  overflow: 'hidden',
  ...fieldOutlineSx(theme),
  // Edited state: ConfigField sets focused when value differs from default (Figma: #F3FF97 border).
  '&.Mui-focused .MuiOutlinedInput-notchedOutline, &.Mui-focused fieldset': {
    border: `1px solid ${theme.palette.secondary.main}`,
  },
  '&.MuiInputBase-root, &.MuiOutlinedInput-root': {
    minHeight: 0,
    height: '48px',
  },
  '& .MuiOutlinedInput-input, & .MuiInputBase-input, & input': {
    ...inputTypographySx(theme),
    flex: 1,
    minWidth: 0,
    padding: 0,
    minHeight: 0,
    height: 'auto',
    textAlign: 'right',
  },
});

/** Single-line TextField wrapper — overrides common MuiInputBase global styles. */
export const singleLineFieldSx: SxProps<Theme> = theme => ({
  ...fieldHelperSx,
  '& .MuiOutlinedInput-root':
    typeof singleLineInputRootSx === 'function'
      ? singleLineInputRootSx(theme)
      : singleLineInputRootSx,
});

/** Proposal summary — fixed 130px height. */
export const proposalSummaryFieldSx: SxProps<Theme> = theme => ({
  ...fieldHelperSx,
  '& .MuiOutlinedInput-root':
    typeof proposalSummaryInputRootSx === 'function'
      ? proposalSummaryInputRootSx(theme)
      : proposalSummaryInputRootSx,
});

/** Date picker OutlinedInput root — single row; 16px inset for chevron via root padding. */
const datePickerInputRootSx: SxProps<Theme> = theme => ({
  ...inputTypographySx(theme),
  display: 'flex',
  flexWrap: 'nowrap',
  padding: '13px 16px',
  justifyContent: 'space-between',
  alignItems: 'center',
  alignSelf: 'stretch',
  width: '100%',
  minHeight: 0,
  height: 'auto',
  backgroundColor: fieldSurfaceBackground,
  borderRadius: '4px',
  overflow: 'hidden',
  ...fieldOutlineSx(theme),
  ...fieldAdornmentSx,
  '&.MuiInputBase-root, &.MuiOutlinedInput-root, &.MuiInputBase-adornedEnd': {
    minHeight: 0,
    height: 'auto',
  },
  '& .MuiOutlinedInput-input, & .MuiInputBase-input, & input': {
    ...inputTypographySx(theme),
    flex: 1,
    minWidth: 0,
    padding: 0,
    minHeight: 0,
    height: 'auto',
  },
  '& .MuiInputAdornment-positionEnd': {
    marginLeft: 'auto',
    marginRight: 0,
    flexShrink: 0,
  },
});

/** Date picker TextField wrapper (helper text spacing + OutlinedInput surface). */
export const datePickerFieldSx: SxProps<Theme> = theme => ({
  ...fieldHelperSx,
  width: '100%',
  '& .MuiOutlinedInput-root':
    typeof datePickerInputRootSx === 'function'
      ? datePickerInputRootSx(theme)
      : datePickerInputRootSx,
});

/** Date picker OutlinedInput root — apply via `slotProps.input.sx` when needed. */
export const datePickerInputSx: SxProps<Theme> = datePickerInputRootSx;

/** Select OutlinedInput root — single row; chevron inset matches date picker. */
const selectInputRootSx: SxProps<Theme> = theme => ({
  ...inputTypographySx(theme),
  display: 'flex',
  flexWrap: 'nowrap',
  padding: '13px 16px',
  justifyContent: 'space-between',
  alignItems: 'center',
  alignContent: 'center',
  alignSelf: 'stretch',
  minHeight: 0,
  height: 'auto',
  backgroundColor: fieldSurfaceBackground,
  borderRadius: '4px',
  overflow: 'hidden',
  ...fieldOutlineSx(theme),
  ...fieldAdornmentSx,
  '&.MuiInputBase-root, &.MuiOutlinedInput-root, &.MuiInputBase-adornedEnd': {
    minHeight: 0,
    height: 'auto',
  },
  '& .MuiOutlinedInput-input, & .MuiInputBase-input, & input, & .MuiSelect-select': {
    ...inputTypographySx(theme),
    flex: 1,
    minWidth: 0,
    padding: 0,
    minHeight: 0,
    height: 'auto',
  },
  '&.MuiSelect-outlined .MuiSelect-select': {
    paddingRight: 0,
  },
  '& .MuiSelect-icon': {
    color: 'common.white',
    fontSize: 16,
    marginLeft: 'auto',
    marginRight: 0,
    flexShrink: 0,
    position: 'static',
    top: 'auto',
    right: 'auto',
  },
  '&.MuiInputBase-sizeSmall': {
    minHeight: 0,
  },
  '& .MuiOutlinedInput-inputSizeSmall': {
    padding: 0,
  },
});

export const selectFieldSx: SxProps<Theme> = selectInputRootSx;

/** DSO config table TextField wrapper (helper text spacing). */
export const configFieldFieldSx: SxProps<Theme> = {
  ...fieldHelperSx,
  width: '238px',
  maxWidth: '100%',
};

/** DSO config table OutlinedInput root — apply via `slotProps.input.sx`. */
export const configFieldInputSx: SxProps<Theme> = configFieldInputRootSx;

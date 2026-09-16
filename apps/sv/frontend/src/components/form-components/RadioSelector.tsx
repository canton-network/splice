// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, FormControlLabel, Radio, RadioGroup, SvgIcon, Typography } from '@mui/material';
import type { SvgIconProps } from '@mui/material';
import React from 'react';
import type { Theme } from '@mui/material/styles';
import { theme } from '@canton-network/splice-common-frontend';

export interface RadioSelectorOption {
  value: string;
  label: string;
  description?: string;
  radioId?: string;
  testId?: string;
  extension?: React.ReactNode;
}

export interface RadioSelectorProps {
  title: string;
  value: string;
  onChange: (value: string) => void;
  options: RadioSelectorOption[];
  id: string;
}

const sectionSx = (theme: Theme) => ({
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'flex-start',
  alignSelf: 'stretch',
  gap: theme.spacing(3),
  width: '100%',
});

const optionLabelColumnSx = (theme: Theme) => ({
  display: 'flex',
  flexDirection: 'column',
  gap: theme.spacing(0.5),
  width: '100%',
});

const optionRowSx = (theme: Theme) => ({
  alignItems: 'flex-start',
  gap: theme.spacing(1),
  m: 0,
  ml: 0,
  mr: 0,
  width: '100%',
  '& .MuiFormControlLabel-label': {
    flex: 1,
    minWidth: 0,
  },
});

const radioSx = {
  alignSelf: 'flex-start',
  p: 0,
  pt: '3px',
  '& .MuiSvgIcon-root': {
    fontSize: 16,
  },
} as const;

const RADIO_RING_PATH =
  'M8.00004 1.33301C4.32004 1.33301 1.33337 4.31967 1.33337 7.99967C1.33337 11.6797 4.32004 14.6663 8.00004 14.6663C11.68 14.6663 14.6667 11.6797 14.6667 7.99967C14.6667 4.31967 11.68 1.33301 8.00004 1.33301ZM8.00004 13.333C5.05337 13.333 2.66671 10.9463 2.66671 7.99967C2.66671 5.05301 5.05337 2.66634 8.00004 2.66634C10.9467 2.66634 13.3334 5.05301 13.3334 7.99967C13.3334 10.9463 10.9467 13.333 8.00004 13.333Z';
const RADIO_DOT_PATH =
  'M7.99996 11.3337C9.84091 11.3337 11.3333 9.84127 11.3333 8.00033C11.3333 6.15938 9.84091 4.66699 7.99996 4.66699C6.15901 4.66699 4.66663 6.15938 4.66663 8.00033C4.66663 9.84127 6.15901 11.3337 7.99996 11.3337Z';

const RadioIcon: React.FC<SvgIconProps & { checked?: boolean }> = ({ checked, ...props }) => (
  <SvgIcon {...props} viewBox="0 0 16 16">
    <path d={RADIO_RING_PATH} fill={theme.palette.secondary.main} />
    {checked && <path d={RADIO_DOT_PATH} fill={theme.palette.secondary.main} />}
  </SvgIcon>
);

export const RadioSelector: React.FC<RadioSelectorProps> = props => {
  const { title, value, onChange, options, id } = props;

  return (
    <Box id={id} data-testid={id} sx={sectionSx}>
      <Typography
        fontSize={12}
        lineHeight="22px"
        fontWeight={600}
        textTransform="uppercase"
        color="common.white"
      >
        {title}
      </Typography>

      <RadioGroup
        value={value}
        onChange={e => onChange(e.target.value)}
        sx={theme => ({
          display: 'flex',
          flexDirection: 'column',
          gap: theme.spacing(3),
          m: 0,
          width: '100%',
        })}
      >
        {options.map(option => (
          <FormControlLabel
            key={option.value}
            value={option.value}
            control={
              <Radio
                id={option.radioId}
                data-testid={option.testId}
                size="small"
                icon={<RadioIcon />}
                checkedIcon={<RadioIcon checked />}
                sx={radioSx}
              />
            }
            label={
              <Box sx={optionLabelColumnSx}>
                <Typography variant="body2" lineHeight="22px" color="text.light">
                  {option.label}
                </Typography>
                {option.description && (
                  <Typography fontSize={12} lineHeight="22px" color="text.light">
                    {option.description}
                  </Typography>
                )}
                {option.extension && (
                  <Box
                    sx={{ width: '100%' }}
                    onClick={e => e.stopPropagation()}
                    onMouseDown={e => e.stopPropagation()}
                  >
                    {option.extension}
                  </Box>
                )}
              </Box>
            }
            sx={optionRowSx}
          />
        ))}
      </RadioGroup>
    </Box>
  );
};

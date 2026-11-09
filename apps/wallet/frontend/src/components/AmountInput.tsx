// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { useMemo } from 'react';
import useAmuletPrice from '../hooks/scan-proxy/useAmuletPrice';
import { parseBigNumber } from '@canton-network/splice-common-frontend';
import { Box, FormControl, InputAdornment, OutlinedInput, Stack, Typography } from '@mui/material';
import { useWalletConfig } from '../utils/config';

interface AmountInputProps {
  idPrefix: string;
  ccAmountText: string;
  setCcAmountText: (amount: string) => void;
}
export const AmountInput: React.FC<AmountInputProps> = ({
  idPrefix,
  setCcAmountText,
  ccAmountText,
}) => {
  const config = useWalletConfig();
  const amuletPriceQuery = useAmuletPrice();
  const usd = useMemo(
    () => amuletPriceQuery.data?.times(parseBigNumber(ccAmountText)),
    [ccAmountText, amuletPriceQuery.data]
  );

  return (
    <Stack direction="column" spacing={1} sx={{ mb: 4 }}>
      <Typography variant="h6">Amount</Typography>
      <Box sx={{ display: 'flex' }}>
        <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
          <OutlinedInput
            id={`${idPrefix}-amulet-amount`}
            type="text"
            value={ccAmountText}
            onChange={event => setCcAmountText(event.target.value)}
            endAdornment={
              <InputAdornment position="end">
                {config.spliceInstanceNames.amuletNameAcronym}
              </InputAdornment>
            }
            aria-describedby="outlined-amount-amulet-helper-text"
            error={parseBigNumber(ccAmountText).lte(0.0)}
            inputProps={{
              'aria-label': 'amount',
            }}
          />
        </FormControl>
        {/* Slight deviation from the original design here. The USD field is below the CC field in the figma designs */}
        <FormControl>
          <OutlinedInput
            disabled
            id={`${idPrefix}-usd-amount`}
            value={usd ?? '...'}
            endAdornment={<InputAdornment position="end">USD</InputAdornment>}
            aria-describedby="outlined-amount-usd-helper-text"
            inputProps={{
              'aria-label': 'amount',
            }}
          />
        </FormControl>
      </Box>
    </Stack>
  );
};
export default AmountInput;

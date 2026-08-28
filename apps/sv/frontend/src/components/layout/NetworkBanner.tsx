// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';

import { Stack, Typography } from '@mui/material';

import { useNetworkInstanceName } from '../../hooks';

const NetworkBanner: React.FC = () => {
  const networkInstanceName = useNetworkInstanceName();
  const knownColors = ['mainnet', 'testnet', 'devnet', 'scratchnet', 'localnet'];
  const networkInstanceNameColor = knownColors.includes(networkInstanceName.toLowerCase())
    ? `colors.${networkInstanceName.toLowerCase()}`
    : 'colors.neutral.30';
  return (
    <Stack
      direction="row"
      alignItems="center"
      justifyContent="center"
      sx={{
        position: 'sticky',
        top: 0,
        zIndex: theme => theme.zIndex.appBar,
        backgroundColor: networkInstanceNameColor,
        color: 'black',
        height: '50px',
        width: '100%',
      }}
    >
      <Typography id="network-instance-name" data-testid="network-instance-name" variant="h6">
        <b>You are on {networkInstanceName} </b>
      </Typography>
    </Stack>
  );
};

export default NetworkBanner;

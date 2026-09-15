// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { Loading, theme } from '@canton-network/splice-common-frontend';
import { Outlet } from 'react-router';

import { Alert, Button, Stack, Typography } from '@mui/material';
import Container from '@mui/material/Container';

import { useWalletSession } from './WalletSessionContext';

/**
 * dApp-mode login gate: instead of OIDC against the SV backend, the user
 * authenticates by connecting a CIP-103 wallet (the wallet gateway or any
 * announced partner wallet). The connected wallet party acts as the
 * VoteDelegation voter party.
 */
export const WalletAuthCheck: React.FC = () => {
  const session = useWalletSession();

  if (session.status === 'connected') {
    return <Outlet />;
  }

  if (session.status === 'initializing') {
    return <Loading />;
  }

  return (
    <Container maxWidth="xs">
      <Stack alignItems="center" paddingTop={16} spacing={4}>
        <Typography
          variant="h5"
          textTransform="uppercase"
          fontFamily={theme.fonts.monospace.fontFamily}
          fontWeight={theme.fonts.monospace.fontWeight}
        >
          Super Validator Operations
        </Typography>
        {session.status === 'wallet_connection_failed' && (
          <Alert severity="error" data-testid="wallet-login-error">
            {session.errorMessage ?? 'Wallet connection failed'}
          </Alert>
        )}
        <Button
          id="connect-wallet-button"
          data-testid="connect-wallet-button"
          variant="pill"
          fullWidth
          size="large"
          onClick={() => void session.connect()}
        >
          Connect Wallet
        </Button>
      </Stack>
    </Container>
  );
};

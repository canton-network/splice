// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Typography, Stack, Box, Container } from '@mui/material';

import Layout from '../components/Layout';
import NetworkInfo from '../components/NetworkInfo';
import TotalAmuletBalance from '../components/TotalAmuletBalance';

const Root: React.FC = () => {
  return (
    <Layout>
      <Box sx={{ bgcolor: 'colors.neutral.15', flex: 1 }}>
        <Container maxWidth="lg" sx={{ marginTop: 2 }}>
          <Stack spacing={2} direction="column" sx={{ mt: 4, justifyContent: 'center' }}>
            <Typography variant="h5">
              Explore, search and find answers to current network configuration details.
            </Typography>

            <div data-testid="circulating-supply-container">
              <TotalAmuletBalance />
            </div>

            <NetworkInfo />
          </Stack>
        </Container>
      </Box>
    </Layout>
  );
};

export default Root;

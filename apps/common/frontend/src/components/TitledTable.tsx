// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { PropsWithChildren } from 'react';

import { Stack, Table, TableContainer, Typography } from '@mui/material';

export const TitledTable: React.FC<
  PropsWithChildren<{ title: string; style?: React.CSSProperties }>
> = ({ children, title, style }) => {
  return (
    <Stack
      style={{ width: '100%' }}
      spacing={0}
      sx={{ justifyContent: 'flex-start', marginTop: 3 }}
    >
      <Typography variant="h4" sx={{ fontWeight: 'bold' }}>
        {title}
      </Typography>
      <TableContainer>
        <Table style={style}>{children}</Table>
      </TableContainer>
    </Stack>
  );
};

export default TitledTable;

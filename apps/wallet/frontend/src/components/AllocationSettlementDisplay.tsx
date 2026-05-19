// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Stack } from '@mui/material';
import Typography from '@mui/material/Typography';
import BftAnsEntry from './BftAnsEntry';
import MetaDisplay from './MetaDisplay';
import { SettlementInfo } from '@daml.js/splice-api-token-allocation-v2/lib/Splice/Api/Token/AllocationV2/module';

const AllocationSettlementDisplay: React.FC<{
  settlement: SettlementInfo;
}> = ({ settlement }) => {
  const { id, cid, executors, meta: settlementMeta } = settlement;

  return (
    <Stack>
      <Stack>
        {id ? (
          <Stack maxWidth="md">
            <Typography className="settlement-id" variant="body2" noWrap>
              Settlement id: {id}
            </Typography>
          </Stack>
        ) : null}
        {cid ? (
          <Stack maxWidth="md">
            <Typography className="settlement-cid" variant="body2" noWrap>
              Settlement cid: {cid}
            </Typography>
          </Stack>
        ) : null}
      </Stack>
      <Stack direction="row" spacing={2}>
        <Stack direction="row" alignItems="center" spacing={1}>
          <Typography variant="body2">Executors:</Typography>
          {executors.map((executor: string, idx: number) => (
            <BftAnsEntry key={idx} partyId={executor} className="settlement-executor" />
          ))}
        </Stack>
      </Stack>
      {Object.keys(settlementMeta.values).length > 0 ? (
        <>
          <Typography variant="h5">Settlement Meta</Typography>
          <MetaDisplay meta={settlementMeta.values} />
        </>
      ) : null}
    </Stack>
  );
};

export default AllocationSettlementDisplay;

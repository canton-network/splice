// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { useAmuletAllocations } from '../hooks/useAmuletAllocations';
import { DisableConditionally, Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import Typography from '@mui/material/Typography';
import { Button, Card, CardContent, Chip, Stack } from '@mui/material';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { AmuletAllocation as AmuletAllocationV1 } from '@daml.js/splice-amulet/lib/Splice/AmuletAllocation';
import { AmuletAllocationV2 } from '@daml.js/splice-amulet/lib/Splice/AmuletAllocationV2';
import TransferLegsDisplay from './TransferLegsDisplay';
import AllocationSettlementDisplay from './AllocationSettlementDisplay';
import { useMutation } from '@tanstack/react-query';
import {
  AmuletAllocation,
  isV2Allocation,
  useWalletClient,
} from '../contexts/WalletServiceContext';
import { AllocationSpecification } from '@daml.js/splice-api-token-allocation-v2/lib/Splice/Api/Token/AllocationV2/module';
import { ContractId } from '@daml/types';

const ListAllocations: React.FC = () => {
  const allocationsQuery = useAmuletAllocations();

  if (allocationsQuery.isLoading) {
    return <Loading />;
  }
  if (allocationsQuery.isError) {
    return (
      <Typography color="error">
        Error loading allocations: {JSON.stringify(allocationsQuery.error)}
      </Typography>
    );
  }

  const allocations = allocationsQuery.data || [];

  return (
    <Stack
      spacing={4}
      direction="column"
      justifyContent="center"
      id="allocations"
      aria-labelledby="allocations-label"
    >
      <Typography mt={6} variant="h4" id="allocations-label">
        Allocations <Chip label={allocations.length} color="success" />
      </Typography>
      {allocations.map(allocation => (
        <AllocationDisplay key={allocation.contractId} allocation={allocation} />
      ))}
    </Stack>
  );
};

const AllocationDisplay: React.FC<{
  allocation: Contract<AmuletAllocation | AmuletAllocationV2>;
}> = ({ allocation }) => {
  const { withdrawAllocation, withdrawAllocationV2 } = useWalletClient();
  const v2 = isV2Allocation(allocation.payload);
  const spec = getAllocationSpec(allocation.payload);
  const { settlement, transferLegs } = spec;
  return (
    <Card className="allocation" variant="outlined">
      <CardContent
        sx={{
          display: 'flex',
          direction: 'row',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <Stack width="100%" spacing={2}>
          <Stack direction="row" alignItems="center" spacing={1}>
            <Chip label={v2 ? 'V2' : 'V1'} color={v2 ? 'primary' : 'default'} size="small" />
          </Stack>
          <AllocationSettlementDisplay settlement={settlement} />
          <TransferLegsDisplay
            parentId={allocation.contractId}
            transferLegs={transferLegs}
            getActionButton={() => (
              <WithdrawAllocationButton
                withdrawFn={() =>
                  v2
                    ? withdrawAllocationV2(allocation.contractId as ContractId<AmuletAllocationV2>)
                    : withdrawAllocation(allocation.contractId as ContractId<AmuletAllocationV1>)
                }
              />
            )}
          />
        </Stack>
      </CardContent>
    </Card>
  );
};

function getAllocationSpec(payload: AmuletAllocation): AllocationSpecification {
  if (isV2Allocation(payload)) {
    return payload.allocation;
  }
  // V1: convert to V2 AllocationSpecification shape
  const v1 = payload.allocation;
  return {
    settlement: {
      executors: [v1.settlement.executor],
      settlementRef: v1.settlement.settlementRef,
      requestedAt: v1.settlement.requestedAt,
      settleAt: v1.settlement.settleBefore,
      settlementDeadline: null,
      meta: v1.settlement.meta,
    },
    transferLegs: [
      {
        transferLegId: v1.transferLegId,
        sender: { owner: v1.transferLeg.sender, provider: null, id: '' },
        receiver: { owner: v1.transferLeg.receiver, provider: null, id: '' },
        amount: v1.transferLeg.amount,
        instrumentId: v1.transferLeg.instrumentId,
        meta: v1.transferLeg.meta,
      },
    ],
    authorizer: { owner: v1.transferLeg.sender, provider: null, id: '' },
  };
}

const WithdrawAllocationButton: React.FC<{
  withdrawFn: () => Promise<void>;
}> = ({ withdrawFn }) => {
  const withdrawAllocationMutation = useMutation({
    mutationFn: async () => {
      return await withdrawFn();
    },
    onSuccess: () => {},
    onError: error => {
      console.error('Failed to withdraw allocation', error);
    },
  });

  return (
    <DisableConditionally
      conditions={[
        {
          disabled: withdrawAllocationMutation.isPending,
          reason: 'Withdrawing allocation...',
        },
      ]}
    >
      <Button
        variant="pill"
        size="small"
        className="allocation-withdraw"
        onClick={() => withdrawAllocationMutation.mutate()}
      >
        Withdraw
      </Button>
    </DisableConditionally>
  );
};

export default ListAllocations;

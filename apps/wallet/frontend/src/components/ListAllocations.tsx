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
import { transferLegSidesToTransferLegs } from '../utils/tokenStandard';
import { usePrimaryParty } from '../hooks';

const ListAllocations: React.FC = () => {
  const primaryPartyId = usePrimaryParty();
  const allocationsQuery = useAmuletAllocations();

  if (allocationsQuery.isLoading || !primaryPartyId) {
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
        <AllocationDisplay
          key={allocation.contractId}
          allocation={allocation}
          userParty={primaryPartyId}
        />
      ))}
    </Stack>
  );
};

const AllocationDisplay: React.FC<{
  userParty: string;
  allocation: Contract<AmuletAllocation>;
}> = ({ userParty, allocation }) => {
  const { withdrawAllocation, withdrawAllocationV2 } = useWalletClient();
  const allocationPayload = allocation.payload;
  const v2 = isV2Allocation(allocationPayload);
  const spec = getAllocationSpec(userParty, allocationPayload);
  const { settlement, transferLegSides } = spec;
  const transferLegs = transferLegSidesToTransferLegs(spec.authorizer, transferLegSides);
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

function getAllocationSpec(
  endUserParty: string,
  payload: AmuletAllocation
): AllocationSpecification {
  if (isV2Allocation(payload)) {
    return payload.allocation;
  }
  // V1: convert to V2 AllocationSpecification shape
  const v1 = payload.allocation;
  const [transferLegSide, otherSideParty]: ['SenderSide' | 'ReceiverSide', string] =
    v1.transferLeg.sender === endUserParty
      ? ['SenderSide', v1.transferLeg.receiver]
      : ['ReceiverSide', v1.transferLeg.sender];
  return {
    settlement: {
      executors: [v1.settlement.executor],
      settlementRef: v1.settlement.settlementRef,
      settlementDeadline: null,
      meta: v1.settlement.meta,
    },
    transferLegSides: [
      {
        transferLegId: payload.allocation.transferLegId,
        meta: payload.allocation.transferLeg.meta,
        side: transferLegSide,
        otherside: { owner: otherSideParty, provider: null, id: '' },
        amount: payload.allocation.transferLeg.amount,
        instrumentId: payload.allocation.transferLeg.instrumentId.id,
      },
    ],
    authorizer: { owner: v1.transferLeg.sender, provider: null, id: '' },
    meta: { values: {} },
    nextIterationFunding: null,
    committed: false,
    admin: payload.allocation.transferLeg.instrumentId.admin,
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

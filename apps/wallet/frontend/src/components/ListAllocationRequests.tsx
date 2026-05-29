// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Button, Card, CardContent, Chip, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';
import { useTokenStandardAllocationRequests } from '../hooks/useTokenStandardAllocationRequests';
import { DisableConditionally, Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import { AllocationRequest as AllocationRequestV1 } from '@daml.js/splice-api-token-allocation-request/lib/Splice/Api/Token/AllocationRequestV1/module';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { AmuletAllocation as AmuletAllocationV1 } from '@daml.js/splice-amulet/lib/Splice/AmuletAllocation';
import { AmuletAllocationV2 } from '@daml.js/splice-amulet/lib/Splice/AmuletAllocationV2';
import { usePrimaryParty } from '../hooks';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { useAmuletAllocations } from '../hooks/useAmuletAllocations';
import TransferLegsDisplay from './TransferLegsDisplay';
import {
  useWalletClient,
  AllocationRequest,
  AmuletAllocation,
  isV2Allocation,
  isV2AllocationRequest,
} from '../contexts/WalletServiceContext';
import { useMutation } from '@tanstack/react-query';
import {
  AllocateAmuletRequest,
  AllocateAmuletV2Request,
} from '@lfdecentralizedtrust/wallet-openapi';
import {
  AllocationSpecification as AllocationSpecificationV2,
  SettlementInfo,
  TransferLegSide,
} from '@daml.js/splice-api-token-allocation-v2/lib/Splice/Api/Token/AllocationV2/module';
import { damlTimestampToOpenApiTimestamp } from '../utils/timestampConversion';
import AllocationSettlementDisplay from './AllocationSettlementDisplay';
import UseGetAmuletRules from '../hooks/scan-proxy/useGetAmuletRules';
import { ContractId } from '@daml/types';
import { getSettlement } from '../utils/tokenStandard';
import AllocationSpecificationDisplay from './AllocationSpecificationDisplay';
import { TextMapDisplay } from './TextMap';

dayjs.extend(relativeTime);

const ListAllocationRequests: React.FC = () => {
  const allocationRequestsQuery = useTokenStandardAllocationRequests();
  const allocationsQuery = useAmuletAllocations();
  const primaryPartyId = usePrimaryParty();
  const amuletRulesQuery = UseGetAmuletRules();

  if (allocationRequestsQuery.isLoading || amuletRulesQuery.isLoading || !primaryPartyId) {
    return <Loading />;
  }
  if (allocationRequestsQuery.isError) {
    return (
      <Typography color="error">
        Error loading allocation requests: {JSON.stringify(allocationRequestsQuery.error)}
      </Typography>
    );
  }
  if (amuletRulesQuery.isError) {
    return (
      <Typography color="error">
        Error loading allocation requests: {JSON.stringify(amuletRulesQuery.error)}
      </Typography>
    );
  }

  const allocationRequests = allocationRequestsQuery.data || [];
  const allocations = allocationsQuery.data || [];

  return (
    <Stack
      spacing={4}
      direction="column"
      justifyContent="center"
      id="allocation-requests"
      aria-labelledby="allocation-requests-label"
    >
      <Typography mt={6} variant="h4" id="allocation-requests-label">
        Allocation Requests <Chip label={allocationRequests.length} color="success" />
      </Typography>
      {allocationRequests.map(ar => (
        <AllocationRequestDisplay
          key={ar.contractId}
          request={ar}
          userParty={primaryPartyId}
          allocations={allocations}
          dso={amuletRulesQuery.data!.contract.payload.dso}
        />
      ))}
    </Stack>
  );
};

const AllocationRequestDisplay: React.FC<{
  request: Contract<AllocationRequest>;
  allocations: Contract<AmuletAllocation>[];
  userParty: string;
  dso: string;
}> = ({ request, userParty, allocations, dso }) => {
  const payload = request.payload;
  const isV2 = isV2AllocationRequest(payload);
  const requestMeta = payload.meta;
  const settlement = getSettlement(payload);

  const { rejectAllocationRequest } = useWalletClient();
  const rejectAllocationRequestMutation = useMutation({
    mutationFn: async () => {
      return await rejectAllocationRequest(request.contractId);
    },
    onSuccess: () => {},
    onError: error => {
      console.error('Failed to reject allocation request', error);
    },
  });

  return (
    <Card className="allocation-request" variant="outlined">
      <CardContent
        sx={{
          display: 'flex',
          direction: 'row',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <Stack width="100%" spacing={2}>
          <Stack direction="row" width="100%" spacing={2}>
            <Chip label={isV2 ? 'V2' : 'V1'} color={isV2 ? 'primary' : 'default'} size="small" />
            <AllocationSettlementDisplay settlement={settlement} />
            <Button
              onClick={() => rejectAllocationRequestMutation.mutate()}
              color="error"
              size="medium"
              className="allocation-request-reject"
              sx={{ alignSelf: 'center', height: 'auto', minHeight: 0 }}
            >
              Reject
            </Button>
          </Stack>
          {Object.keys(requestMeta.values).length > 0 ? (
            <>
              <Typography variant="h5">Request Meta</Typography>
              <TextMapDisplay textMap={requestMeta.values} />
            </>
          ) : null}
          {isV2 ? (
            payload.allocations.map((allocationSpec, idx) => {
              // key as index is fine because they won't move around
              return (
                <Stack key={idx}>
                  <AllocationSpecificationDisplay
                    parentId={request.contractId}
                    spec={allocationSpec}
                    // for v2, the action button operates over an allocation specification, not per transfer-leg
                    getActionButton={() => (
                      <V2AllocationRequestActionButton
                        requestCid={request.contractId}
                        allocationIndex={idx}
                        allocationSpecification={allocationSpec}
                        settlement={payload.settlement}
                        allocations={allocations}
                        userParty={userParty}
                        dso={dso}
                      />
                    )}
                  />
                </Stack>
              );
            })
          ) : (
            <TransferLegsDisplay
              parentId={request.contractId}
              transferLegs={payload.transferLegs}
              getActionButton={(transferLegId, parentComponentId) => (
                <V1AllocationRequestActionButton
                  parentComponentId={parentComponentId}
                  allocationRequest={request as Contract<AllocationRequestV1>}
                  allocations={allocations}
                  transferLegId={transferLegId}
                  userParty={userParty}
                  dso={dso}
                />
              )}
            />
          )}
        </Stack>
      </CardContent>
    </Card>
  );
};

/** V2: one Accept button per request, filters amulet legs for userParty */
const V2AllocationRequestActionButton: React.FC<{
  requestCid: string;
  allocationIndex: number;
  settlement: SettlementInfo;
  allocationSpecification: AllocationSpecificationV2;
  allocations: Contract<AmuletAllocation>[];
  userParty: string;
  dso: string;
}> = ({
  requestCid,
  allocationIndex,
  settlement,
  allocationSpecification,
  userParty,
  allocations,
  dso,
}) => {
  const validSpecification =
    allocationSpecification.admin === dso &&
    // basicAccount check: authorizer matches basicAccount(userParty)
    allocationSpecification.authorizer.owner === userParty &&
    !allocationSpecification.authorizer.provider &&
    allocationSpecification.authorizer.id === '';

  const amuletLegSidesForUser = allocationSpecification.transferLegSides.filter(
    side => side.instrumentId === 'Amulet'
  );
  const canAccept = validSpecification && amuletLegSidesForUser.length > 0;

  const correspondingAllocation = allocations.find(alloc =>
    isAllocationForRequest(alloc, settlement, allocationSpecification)
  );

  const hasExistingAllocation = !!correspondingAllocation;

  const { createAllocationV2, withdrawAllocationV2 } = useWalletClient();
  const createAllocationV2Mutation = useMutation({
    mutationFn: async () => {
      const req = openApiV2RequestFromAllocationRequest(
        settlement,
        allocationSpecification,
        amuletLegSidesForUser
      );
      return await createAllocationV2(req);
    },
    onSuccess: () => {},
    onError: error => {
      console.error('Failed to submit allocation', error);
    },
  });

  const withdrawAllocationV2Mutation = useMutation({
    mutationFn: async () => {
      if (correspondingAllocation) {
        return await withdrawAllocationV2(
          correspondingAllocation.contractId as ContractId<AmuletAllocationV2>
        );
      } else {
        throw new Error("This mutation shouldn't be called without a corresponding allocation");
      }
    },
    onSuccess: () => {},
    onError: error => {
      console.error('Failed to withdraw allocation', error);
    },
  });

  if (!canAccept) return null;

  if (hasExistingAllocation) {
    return (
      <DisableConditionally
        conditions={[
          {
            disabled: withdrawAllocationV2Mutation.isPending,
            reason: 'Withdrawing allocation...',
          },
        ]}
      >
        <Button
          variant="pill"
          size="small"
          id={`allocation-request-${requestCid}-${allocationIndex}-withdraw`}
          className="allocation-withdraw"
          onClick={() => withdrawAllocationV2Mutation.mutate()}
        >
          Withdraw
        </Button>
      </DisableConditionally>
    );
  }

  return (
    <DisableConditionally
      conditions={[
        { disabled: createAllocationV2Mutation.isPending, reason: 'Creating allocation...' },
      ]}
    >
      <Button
        variant="pill"
        size="small"
        id={`allocation-request-${requestCid}-${allocationIndex}-accept`}
        className="allocation-request-accept"
        onClick={() => createAllocationV2Mutation.mutate()}
      >
        Accept
      </Button>
    </DisableConditionally>
  );
};

/** V1: one Accept/Withdraw button per transfer leg */
const V1AllocationRequestActionButton: React.FC<{
  parentComponentId: string;
  allocationRequest: Contract<AllocationRequestV1>;
  allocations: Contract<AmuletAllocation>[];
  userParty: string;
  transferLegId: string;
  dso: string;
}> = ({ parentComponentId, allocationRequest, transferLegId, userParty, allocations, dso }) => {
  const transferLeg = allocationRequest.payload.transferLegs[transferLegId];
  const actionAllowed =
    transferLeg.sender === userParty &&
    transferLeg.instrumentId.id === 'Amulet' &&
    transferLeg.instrumentId.admin === dso;
  const correspondingAllocation = allocations.find(alloc =>
    isAllocationForTransferLeg(alloc, allocationRequest, transferLegId)
  );
  const alreadyAccepted = !!correspondingAllocation;

  const { createAllocation, withdrawAllocation } = useWalletClient();
  const createAllocationMutation = useMutation({
    mutationFn: async () => {
      const payload: AllocateAmuletRequest = openApiV1RequestFromTransferLeg(
        allocationRequest.payload.settlement,
        transferLeg,
        transferLegId
      );
      return await createAllocation(payload);
    },
    onSuccess: () => {},
    onError: error => {
      console.error('Failed to submit allocation', error);
    },
  });
  const withdrawAllocationMutation = useMutation({
    mutationFn: async () => {
      if (correspondingAllocation) {
        return await withdrawAllocation(
          correspondingAllocation.contractId as ContractId<AmuletAllocationV1>
        );
      } else {
        throw new Error("This mutation shouldn't be called without a corresponding allocation");
      }
    },
    onSuccess: () => {},
    onError: error => {
      console.error('Failed to withdraw allocation', error);
    },
  });

  if (!actionAllowed) return null;
  if (alreadyAccepted) {
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
          id={`${parentComponentId}-withdraw`}
          variant="pill"
          size="small"
          className="allocation-withdraw"
          onClick={() => withdrawAllocationMutation.mutate()}
        >
          Withdraw
        </Button>
      </DisableConditionally>
    );
  }
  return (
    <DisableConditionally
      conditions={[
        { disabled: createAllocationMutation.isPending, reason: 'Creating allocation...' },
      ]}
    >
      <Button
        id={`${parentComponentId}-accept`}
        variant="pill"
        size="small"
        className="allocation-request-accept"
        onClick={() => createAllocationMutation.mutate()}
      >
        Accept
      </Button>
    </DisableConditionally>
  );
};

function isAllocationForRequest(
  allocation: Contract<AmuletAllocation>,
  requestSettlement: SettlementInfo,
  allocationSpec: AllocationSpecificationV2
): boolean {
  const allocationPayload = allocation.payload;
  const isV2 = isV2Allocation(allocationPayload);
  const allocationSettlementId = isV2
    ? allocationPayload.settlement.id
    : allocationPayload.allocation.settlement.settlementRef.id;
  const allocationSettlementCid = isV2
    ? allocationPayload.settlement.cid
    : allocationPayload.allocation.settlement.settlementRef.cid;
  const sameTransferLegs = isV2
    ? allocationPayload.allocation.transferLegSides.map(allocSide =>
        allocationSpec.transferLegSides.some(
          specSide =>
            allocSide.transferLegId === specSide.transferLegId && allocSide.side === specSide.side
        )
      )
    : allocationSpec.transferLegSides.some(
        side => side.transferLegId === allocationPayload.allocation.transferLegId
      );
  const sameExecutor = isV2
    ? allocationPayload.settlement.executors.every(
        executor => requestSettlement.executors.indexOf(executor) !== -1
      )
    : requestSettlement.executors.indexOf(allocationPayload.allocation.settlement.executor) !== -1;
  const allocationMeta = isV2
    ? allocationPayload.settlement.meta
    : allocationPayload.allocation.settlement.meta;
  const sameMeta = Object.entries(allocationMeta.values).every(
    ([key, value]) => value === requestSettlement.meta.values[key]
  );

  return (
    allocationSettlementId === requestSettlement.id &&
    allocationSettlementCid === requestSettlement.cid &&
    sameTransferLegs &&
    sameExecutor &&
    sameMeta
  );
}

function isAllocationForTransferLeg(
  allocation: Contract<AmuletAllocation>,
  allocationRequest: Contract<AllocationRequestV1>,
  legId: string
): boolean {
  let sameExecutor: boolean;
  let sameLegId: boolean;
  let allocationSettlementId: string;
  let allocationSettlementCid: string | null;
  if (isV2Allocation(allocation.payload)) {
    sameExecutor = allocation.payload.settlement.executors.some(
      e => e === allocationRequest.payload.settlement.executor
    );
    sameLegId = allocation.payload.allocation.transferLegSides.some(
      side => side.transferLegId === legId
    );
    allocationSettlementId = allocation.payload.settlement.id;
    allocationSettlementCid = allocation.payload.settlement.cid;
  } else {
    sameExecutor =
      allocation.payload.allocation.settlement.executor ===
      allocationRequest.payload.settlement.executor;
    sameLegId = allocation.payload.allocation.transferLegId === legId;
    allocationSettlementId = allocation.payload.allocation.settlement.settlementRef.id;
    allocationSettlementCid = allocation.payload.allocation.settlement.settlementRef.cid;
  }
  return (
    sameExecutor &&
    allocationSettlementId === allocationRequest.payload.settlement.settlementRef.id &&
    allocationSettlementCid === allocationRequest.payload.settlement.settlementRef.cid &&
    sameLegId
  );
}

/** V1: build AllocateAmuletRequest from a single transfer leg, copying metadata */
export function openApiV1RequestFromTransferLeg(
  settlement: AllocationRequestV1['settlement'],
  transferLeg: AllocationRequestV1['transferLegs'][string],
  transferLegId: string
): AllocateAmuletRequest {
  return {
    settlement: {
      executor: settlement.executor,
      settlement_ref: {
        id: settlement.settlementRef.id,
        cid: settlement.settlementRef.cid as string,
      },
      requested_at: damlTimestampToOpenApiTimestamp(settlement.requestedAt),
      allocate_before: damlTimestampToOpenApiTimestamp(settlement.allocateBefore),
      settle_before: damlTimestampToOpenApiTimestamp(settlement.settleBefore),
      meta: { ...settlement.meta.values, ...transferLeg.meta.values },
    },
    transfer_leg_id: transferLegId,
    transfer_leg: {
      receiver: transferLeg.receiver,
      amount: transferLeg.amount,
      meta: transferLeg.meta.values,
    },
  };
}

/** V2: build AllocateAmuletV2Request from settlement + filtered transfer legs */
export function openApiV2RequestFromAllocationRequest(
  settlement: SettlementInfo,
  allocationSpecification: AllocationSpecificationV2,
  transferLegSides: TransferLegSide[]
): AllocateAmuletV2Request {
  return {
    settlement: {
      executors: settlement.executors,
      settlement_ref: {
        id: settlement.id,
        cid: settlement.cid as string,
      },
      meta: settlement.meta.values,
      ...(allocationSpecification.settlementDeadline
        ? {
            settlement_deadline: damlTimestampToOpenApiTimestamp(
              allocationSpecification.settlementDeadline
            ),
          }
        : {}),
    },
    transfer_leg_sides: transferLegSides.map(side => ({
      transfer_leg_id: side.transferLegId,
      amount: side.amount,
      otherside: side.otherside.owner ?? '<unexpected: missing owner party>',
      side: side.side === 'SenderSide' ? 'SENDERSIDE' : 'RECEIVERSIDE',
      meta: side.meta.values,
    })),
    committed: allocationSpecification.committed,
    meta: allocationSpecification.meta.values,
    ...(allocationSpecification.nextIterationFunding
      ? { next_iteration_funding: allocationSpecification.nextIterationFunding }
      : {}),
  };
}

export default ListAllocationRequests;

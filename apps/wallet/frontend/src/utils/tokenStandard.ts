// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { SettlementInfo } from '@daml.js/splice-api-token-allocation-v2-1.0.0/lib/Splice/Api/Token/AllocationV2';
import { Account } from '@daml.js/splice-api-token-holding-v2-1.0.0/lib/Splice/Api/Token/HoldingV2';
import { AllocationRequest, isV2AllocationRequest } from 'src/contexts/WalletServiceContext';

export function basicAccount(party: string): Account {
  return { owner: party, provider: null, id: '' };
}

export function getSettlement(request: AllocationRequest): SettlementInfo {
  if (isV2AllocationRequest(request)) {
    return request.settlement;
  } else {
    return {
      executors: [request.settlement.executor],
      id: request.settlement.settlementRef.id,
      cid: request.settlement.settlementRef.cid,
      meta: request.settlement.meta,
    };
  }
}

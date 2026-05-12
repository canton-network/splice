// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  TransferLeg,
  TransferLegSide,
} from '@daml.js/splice-api-token-allocation-v2-1.0.0/lib/Splice/Api/Token/AllocationV2';
import { Account } from '@daml.js/splice-api-token-holding-v2-1.0.0/lib/Splice/Api/Token/HoldingV2';

export function transferLegSidesToTransferLegs(
  authorizer: Account,
  sides: TransferLegSide[]
): TransferLeg[] {
  return sides.map(side => {
    const sender: Account = side.side === 'SenderSide' ? authorizer : side.otherside;
    const receiver = side.side === 'ReceiverSide' ? authorizer : side.otherside;
    return {
      transferLegId: side.transferLegId,
      sender,
      receiver,
      amount: side.amount,
      instrumentId: side.instrumentId,
      meta: side.meta,
    };
  });
}

export function basicAccount(party: string): Account {
  return { owner: party, provider: null, id: '' };
}

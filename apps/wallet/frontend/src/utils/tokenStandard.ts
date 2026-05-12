import {
  TransferLeg,
  TransferLegSide,
} from '@daml.js/splice-api-token-allocation-v2-1.0.0/lib/Splice/Api/Token/AllocationV2';

export function transferLegSidesToTransferLegs(sides: TransferLegSide[]): TransferLeg[] {
  const groupedById = Object.groupBy(sides, side => side.transferLegId);
  return Object.entries(groupedById).map(([legId, sides]) => {
    const receiverSide = sides!.find(side => side.side === 'ReceiverSide')!;
    const senderSide = sides!.find(side => side.side === 'SenderSide')!;
    return {
      transferLegId: legId,
      sender: { owner: receiverSide.otherside.owner, provider: null, id: '' },
      receiver: { owner: senderSide.otherside.owner, provider: null, id: '' },
      // For these fields, it doesn't matter whether we use senderSide or receiverSide because they should match
      amount: senderSide.amount,
      instrumentId: senderSide.instrumentId,
      meta: senderSide.meta,
    };
  });
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import BigNumber from 'bignumber.js';
import { Counter, Trend } from 'k6/metrics';

import { syncRetryUndefined } from '../../utils';
import { FeaturedAppRightSpec, GetBalanceResponse, damlFeaturedAppKinds } from './models';
import { ValidatorClient } from './validator';

const createOfferLatency = new Trend('create_offer_latency', true);
const offerSyncLatency = new Trend('offer_sync_latency', true);
const acceptOfferLatency = new Trend('accept_offer_latency', true);

const transfersCompleted = new Counter('transfers_completed');
const transfersFailed = new Counter('transfers_failed');

export function doIfOnboarded(validatorClient: ValidatorClient, action: () => void): void {
  const userStatus = validatorClient.v0.wallet.userStatus();
  if (userStatus?.user_onboarded && userStatus?.user_wallet_installed) {
    const wanted = validatorClient.featured;
    if (!userStatus.has_featured_app_right && wanted) {
      validatorClient.v0.wallet.selfGrantFeatureAppRight(wanted);
    } else if (userStatus.has_featured_app_right && wanted === false) {
      validatorClient.v0.wallet.cancelFeaturedAppRights();
    } else if (
      userStatus.has_featured_app_right &&
      wanted &&
      hasOtherFeaturedAppRight(validatorClient, userStatus.party_id, wanted)
    ) {
      // Wrong config: cancel the right, a later iteration grants it with the wanted config
      validatorClient.v0.wallet.cancelFeaturedAppRights();
    } else {
      action();
    }
  } else {
    validatorClient.v0.register();
  }
}

// Whether the party's featured app right is known to differ from the wanted config.
// Returns false if the right can't be looked up, so that we never cancel rights based on guesses.
function hasOtherFeaturedAppRight(
  validatorClient: ValidatorClient,
  partyId: string,
  wanted: FeaturedAppRightSpec,
): boolean {
  const right =
    validatorClient.v0.scanProxy.lookupFeaturedAppRight(partyId)?.featured_app_right?.payload;
  if (right === undefined) {
    return false;
  }
  const actualWeight = right.activityWeight == null ? undefined : Number(right.activityWeight);
  const actualOpsParties = right.opsParties ?? undefined;
  const matches =
    (right.appKind ?? undefined) === (wanted.kind && damlFeaturedAppKinds[wanted.kind]) &&
    actualWeight === wanted.weight &&
    JSON.stringify(actualOpsParties) === JSON.stringify(wanted.opsParties);
  if (!matches) {
    console.log(
      `Featured app right of ${partyId} is ${JSON.stringify(right)}, wanted ${JSON.stringify(wanted)}`,
    );
  }
  return !matches;
}

export function sendAndWaitForTransferOffer(
  sender: ValidatorClient,
  receiver: ValidatorClient,
  amount: string,
): void {
  const startTime = Date.now();

  const receiverParty = receiver.partyId()!; // assumes doIfOnboarded has been called for receiver
  try {
    const transferOffer = sender.v0.wallet.createTransferOffer(amount, receiverParty);

    if (transferOffer) {
      const createdOfferTime = Date.now();
      createOfferLatency.add(createdOfferTime - startTime);

      const receivingOffer = syncRetryUndefined(() =>
        receiver.v0.wallet
          .listTransferOffers()
          ?.transfers.find(o => o.contract_id === transferOffer.output.transfer_instruction_cid),
      );

      const syncOfferTime = Date.now();
      offerSyncLatency.add(syncOfferTime - createdOfferTime);

      if (receivingOffer) {
        receiver.v0.wallet.acceptTransferOffer(receivingOffer.contract_id);

        const acceptOfferTime = Date.now();
        acceptOfferLatency.add(acceptOfferTime - syncOfferTime);

        transfersCompleted.add(1);
      }
    } else {
      console.error('We expected a transfer offer from the sending side');
      transfersFailed.add(1);
    }
  } catch (e) {
    console.error(`Transfer failed with ${e}`);
    transfersFailed.add(1);
  }
}

export function waitForBalance(
  client: ValidatorClient,
  minBalanceThreshold: number,
  topUpAmount: string,
  admin: ValidatorClient,
  isDevNet: boolean,
): GetBalanceResponse | undefined {
  const balance = syncRetryUndefined(client.v0.wallet.getBalance);
  if (balance && BigNumber(balance?.effective_unlocked_qty).lte(minBalanceThreshold)) {
    if (isDevNet) {
      client.v0.wallet.tap(topUpAmount);
      const balance = syncRetryUndefined(client.v0.wallet.getBalance);
      return balance;
    } else {
      // In non-devnet environments, users must be sent amulets from the validator admin. The validator admin will have to have
      //  its wallet balance topped up manually
      sendAndWaitForTransferOffer(admin, client, topUpAmount);
      const balance = syncRetryUndefined(client.v0.wallet.getBalance);

      if (!balance || BigNumber(balance.effective_unlocked_qty).lte(minBalanceThreshold)) {
        console.error('Failed to topup the client from the validator operator');
      }
    }
  } else {
    return balance;
  }
}

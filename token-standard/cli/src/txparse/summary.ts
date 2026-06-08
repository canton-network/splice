// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { getMetaKeyValue, Meta } from "../apis/ledger-api-utils";
import { BurnedMetaKey } from "../constants";
import {
  Holding,
  HoldingLock,
  HoldingsChange,
  HoldingsChangeSummary,
  TokenStandardEvent,
} from "./types";
import BigNumber from "bignumber.js";

export function sumHoldingsChange(
  change: HoldingsChange,
  filter: (owner: string, lock: HoldingLock | null) => boolean,
): BigNumber {
  return sumHoldings(
    change.creates.filter((create) => filter(create.owner, create.lock)),
  ).minus(
    sumHoldings(
      change.archives.filter((archive) => filter(archive.owner, archive.lock)),
    ),
  );
}

function sumHoldings(holdings: Holding[]): BigNumber {
  return BigNumber.sum(
    ...holdings.map((h) => h.amount).concat(["0"]), // avoid NaN
  );
}

export function computeAmountChanges(
  children: HoldingsChange,
  meta: Meta,
  partyId: string,
): { mintAmount: string; burnAmount: string } {
  const burnAmount = BigNumber(getMetaKeyValue(BurnedMetaKey, meta) || "0");
  const partyHoldingAmountChange = sumHoldingsChange(
    children,
    (owner) => owner === partyId,
  );
  const otherPartiesHoldingAmountChange = sumHoldingsChange(
    children,
    (owner) => owner !== partyId,
  );
  const mintAmount = partyHoldingAmountChange
    .plus(burnAmount)
    .plus(otherPartiesHoldingAmountChange);
  return {
    burnAmount: burnAmount.toString(),
    mintAmount: mintAmount.toString(),
  };
}

export function computeSummary(
  changes: HoldingsChange,
  partyId: string,
): HoldingsChangeSummary {
  const amountChange = sumHoldingsChange(changes, (owner) => owner === partyId);
  const outputAmount = sumHoldings(changes.creates);
  const inputAmount = sumHoldings(changes.archives);
  return {
    amountChange: amountChange.toString(),
    numOutputs: changes.creates.length,
    outputAmount: outputAmount.toString(),
    numInputs: changes.archives.length,
    inputAmount: inputAmount.toString(),
  };
}

export function holdingChangesNonEmpty(event: TokenStandardEvent): boolean {
  return (
    event.unlockedHoldingsChange.creates.length > 0 ||
    event.unlockedHoldingsChange.archives.length > 0 ||
    event.lockedHoldingsChange.creates.length > 0 ||
    event.lockedHoldingsChange.archives.length > 0
  );
}

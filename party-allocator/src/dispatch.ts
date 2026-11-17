// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { logger } from "./logger.js";

// Runs runItem for each item returned by nextItem with at most limit() items in flight.
// Returns once nextItem returns undefined and nothing is in flight. nextItem is called again after
// each completion, so items that are re-queued by runItem are picked up.
export async function dispatch<T>(
  nextItem: () => T | undefined,
  runItem: (item: T) => Promise<void>,
  limit: () => number,
): Promise<void> {
  let inFlight = 0;
  let wake: (() => void) | undefined;
  const waitForCompletion = () =>
    new Promise<void>((resolve) => {
      wake = resolve;
    });
  const onCompletion = () => {
    inFlight--;
    const w = wake;
    wake = undefined;
    w?.();
  };
  // eslint-disable-next-line no-constant-condition
  while (true) {
    while (inFlight >= limit()) {
      await waitForCompletion();
    }
    const item = nextItem();
    if (item === undefined) {
      if (inFlight === 0) {
        return;
      }
      await waitForCompletion();
      continue;
    }
    inFlight++;
    runItem(item)
      .catch((e: unknown) => logger.error(`Unexpected error: ${e}`))
      .finally(onCompletion);
  }
}

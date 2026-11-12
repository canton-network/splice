// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { logger } from "./logger.js";

// AIMD concurrency limit:
// - Until the first failure (slow start), the limit grows by one per success, i.e. it roughly
//   doubles each time all in-flight tasks complete.
// - Afterwards, it grows by one per `limit` consecutive successes.
// - Failures halve the limit, at most once per decreaseCooldownMs so that a burst of failures
//   caused by a single overload event only counts once.
// - After a failure, the limit is held for holdAfterFailureMs before growing again.
export class AdaptiveConcurrency {
  private currentLimit = 1;
  private successStreak = 0;
  private slowStart = true;
  private lastFailure = Number.NEGATIVE_INFINITY;
  private lastDecrease = Number.NEGATIVE_INFINITY;

  constructor(
    private readonly maxLimit: number,
    private readonly decreaseCooldownMs: number = 30_000,
    private readonly holdAfterFailureMs: number = 60_000,
    private readonly onChange: (limit: number) => void = () => {},
    private readonly now: () => number = Date.now,
  ) {
    this.onChange(this.currentLimit);
  }

  get limit(): number {
    return this.currentLimit;
  }

  recordSuccess(): void {
    if (this.currentLimit >= this.maxLimit) {
      return;
    }
    if (this.now() - this.lastFailure < this.holdAfterFailureMs) {
      return;
    }
    this.successStreak++;
    if (this.slowStart || this.successStreak >= this.currentLimit) {
      this.setLimit(this.currentLimit + 1, "increasing");
    }
  }

  recordFailure(): void {
    const now = this.now();
    this.lastFailure = now;
    this.successStreak = 0;
    this.slowStart = false;
    if (now - this.lastDecrease < this.decreaseCooldownMs) {
      return;
    }
    this.lastDecrease = now;
    if (this.currentLimit > 1) {
      this.setLimit(
        Math.max(1, Math.floor(this.currentLimit / 2)),
        "decreasing",
      );
    }
  }

  private setLimit(limit: number, action: string) {
    logger.info(`${action} parallelism from ${this.currentLimit} to ${limit}`);
    this.currentLimit = limit;
    this.successStreak = 0;
    this.onChange(limit);
  }
}

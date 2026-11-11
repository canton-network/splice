// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { logger } from "./logger.js";

// Concurrency limit that grows by one per `limit` consecutive successes and
// shrinks by one on failures (at most once per decreaseCooldownMs).
// After a failure, it is held for holdAfterFailureMs before growing again.
export class AdaptiveConcurrency {
  private currentLimit = 1;
  private successStreak = 0;
  private lastFailure = Number.NEGATIVE_INFINITY;
  private lastDecrease = Number.NEGATIVE_INFINITY;

  constructor(
    private readonly maxLimit: number,
    private readonly decreaseCooldownMs: number = 60_000,
    private readonly holdAfterFailureMs: number = 5 * 60_000,
    private readonly onChange: (limit: number) => void = () => {},
    private readonly now: () => number = Date.now,
  ) {
    this.onChange(this.currentLimit);
  }

  get limit(): number {
    return this.currentLimit;
  }

  recordSuccess(): void {
    if (this.now() - this.lastFailure < this.holdAfterFailureMs) {
      return;
    }
    this.successStreak++;
    if (
      this.successStreak >= this.currentLimit &&
      this.currentLimit < this.maxLimit
    ) {
      this.setLimit(this.currentLimit + 1, "increasing");
    }
  }

  recordFailure(): void {
    const now = this.now();
    this.lastFailure = now;
    this.successStreak = 0;
    if (now - this.lastDecrease < this.decreaseCooldownMs) {
      return;
    }
    this.lastDecrease = now;
    if (this.currentLimit > 1) {
      this.setLimit(this.currentLimit - 1, "decreasing");
    }
  }

  private setLimit(limit: number, action: string) {
    logger.info(`${action} parallelism from ${this.currentLimit} to ${limit}`);
    this.currentLimit = limit;
    this.successStreak = 0;
    this.onChange(limit);
  }
}

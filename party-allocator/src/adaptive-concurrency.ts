// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { logger } from "./logger.js";

// AIMD concurrency limit: doubles (slow start) until the first failure, then
// grows by one per `limit` successes, and halves on failures (at most once per cooldown).
export class AdaptiveConcurrency {
  private currentLimit = 1;
  private successStreak = 0;
  private slowStart = true;
  private lastDecrease = 0;

  constructor(
    private readonly maxLimit: number,
    private readonly decreaseCooldownMs: number = 5000,
    private readonly onChange: (limit: number) => void = () => {},
  ) {
    this.onChange(this.currentLimit);
  }

  get limit(): number {
    return this.currentLimit;
  }

  recordSuccess(): void {
    this.successStreak++;
    if (
      this.successStreak >= this.currentLimit &&
      this.currentLimit < this.maxLimit
    ) {
      const next = this.slowStart
        ? this.currentLimit * 2
        : this.currentLimit + 1;
      this.setLimit(Math.min(next, this.maxLimit), "increasing");
    }
  }

  recordFailure(): void {
    this.successStreak = 0;
    this.slowStart = false;
    const now = Date.now();
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

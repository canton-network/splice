// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// Caches the result of fetch for ttlMs. Concurrent callers share a single in-flight fetch
// and failed fetches are not cached.
export class CachedValue<T> {
  private value: Promise<T> | undefined;
  private fetchedAt = Number.NEGATIVE_INFINITY;

  constructor(
    private readonly fetch: () => Promise<T>,
    private readonly ttlMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  get(): Promise<T> {
    if (this.value === undefined || this.now() - this.fetchedAt >= this.ttlMs) {
      const value = this.fetch();
      this.value = value;
      this.fetchedAt = this.now();
      value.catch(() => {
        if (this.value === value) {
          this.invalidate();
        }
      });
    }
    return this.value;
  }

  invalidate(): void {
    this.value = undefined;
  }
}

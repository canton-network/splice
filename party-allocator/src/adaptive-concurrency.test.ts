// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from "vitest";
import { AdaptiveConcurrency } from "./adaptive-concurrency.js";

function setup(maxLimit: number) {
  let time = 0;
  const limits: number[] = [];
  const concurrency = new AdaptiveConcurrency(
    maxLimit,
    30_000,
    60_000,
    (l) => limits.push(l),
    () => time,
  );
  return {
    concurrency,
    limits,
    advance: (ms: number) => {
      time += ms;
    },
  };
}

describe("AdaptiveConcurrency", () => {
  it("grows by one per success until the first failure", () => {
    const { concurrency } = setup(20);
    for (let i = 0; i < 19; i++) {
      concurrency.recordSuccess();
    }
    expect(concurrency.limit).toBe(20);
    concurrency.recordSuccess();
    expect(concurrency.limit).toBe(20);
  });

  it("halves on failure and only once per cooldown", () => {
    const { concurrency, advance } = setup(20);
    for (let i = 0; i < 19; i++) {
      concurrency.recordSuccess();
    }
    concurrency.recordFailure();
    expect(concurrency.limit).toBe(10);
    concurrency.recordFailure();
    expect(concurrency.limit).toBe(10);
    advance(30_000);
    concurrency.recordFailure();
    expect(concurrency.limit).toBe(5);
    for (let i = 0; i < 4; i++) {
      advance(30_000);
      concurrency.recordFailure();
    }
    expect(concurrency.limit).toBe(1);
  });

  it("holds after a failure and then grows additively", () => {
    const { concurrency, advance } = setup(20);
    for (let i = 0; i < 9; i++) {
      concurrency.recordSuccess();
    }
    concurrency.recordFailure();
    expect(concurrency.limit).toBe(5);
    for (let i = 0; i < 10; i++) {
      concurrency.recordSuccess();
    }
    expect(concurrency.limit).toBe(5);
    advance(60_000);
    for (let i = 0; i < 4; i++) {
      concurrency.recordSuccess();
    }
    expect(concurrency.limit).toBe(5);
    concurrency.recordSuccess();
    expect(concurrency.limit).toBe(6);
  });

  it("reports every change", () => {
    const { concurrency, limits } = setup(3);
    concurrency.recordSuccess();
    concurrency.recordSuccess();
    concurrency.recordFailure();
    expect(limits).toEqual([1, 2, 3, 1]);
  });
});

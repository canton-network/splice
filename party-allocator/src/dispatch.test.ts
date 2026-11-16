// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from "vitest";
import { dispatch } from "./dispatch.js";

const tick = () => new Promise((resolve) => setTimeout(resolve, 1));

describe("dispatch", () => {
  it("respects a changing limit and runs every item", async () => {
    const items = [...Array(20).keys()];
    let limit = 2;
    let inFlight = 0;
    let maxInFlight = 0;
    const done: number[] = [];
    await dispatch(
      () => items.shift(),
      async (item) => {
        inFlight++;
        maxInFlight = Math.max(maxInFlight, inFlight);
        await tick();
        inFlight--;
        done.push(item);
        if (done.length === 5) {
          limit = 4;
        }
      },
      () => limit,
    );
    expect(done.sort((a, b) => a - b)).toEqual([...Array(20).keys()]);
    expect(maxInFlight).toBe(4);
  });

  it("picks up items re-queued by in-flight tasks", async () => {
    const queue = [1];
    const runs: number[] = [];
    await dispatch(
      () => queue.shift(),
      async (item) => {
        runs.push(item);
        await tick();
        if (runs.length < 3) {
          queue.push(item);
        }
      },
      () => 1,
    );
    expect(runs).toEqual([1, 1, 1]);
  });

  it("keeps going when a task rejects", async () => {
    const queue = [1, 2];
    const runs: number[] = [];
    await dispatch(
      () => queue.shift(),
      async (item) => {
        runs.push(item);
        throw new Error("boom");
      },
      () => 1,
    );
    expect(runs).toEqual([1, 2]);
  });
});

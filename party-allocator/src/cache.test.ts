// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it, vi } from "vitest";
import { CachedValue } from "./cache.js";

describe("CachedValue", () => {
  it("shares one fetch between concurrent callers and refetches after the ttl", async () => {
    let time = 0;
    let n = 0;
    const fetch = vi.fn(async () => ++n);
    const cache = new CachedValue(fetch, 1000, () => time);
    expect(await Promise.all([cache.get(), cache.get()])).toEqual([1, 1]);
    time = 999;
    expect(await cache.get()).toBe(1);
    time = 1000;
    expect(await cache.get()).toBe(2);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("refetches after invalidate", async () => {
    let n = 0;
    const cache = new CachedValue(
      async () => ++n,
      1000,
      () => 0,
    );
    expect(await cache.get()).toBe(1);
    cache.invalidate();
    expect(await cache.get()).toBe(2);
  });

  it("does not cache failures", async () => {
    const fetch = vi
      .fn()
      .mockRejectedValueOnce(new Error("boom"))
      .mockResolvedValue(42);
    const cache = new CachedValue(fetch, 1000, () => 0);
    await expect(cache.get()).rejects.toThrow("boom");
    expect(await cache.get()).toBe(42);
  });
});

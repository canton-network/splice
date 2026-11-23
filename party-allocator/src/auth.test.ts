// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it, vi } from "vitest";
import { ClientCredentialsTokenSource } from "./auth.js";

describe("ClientCredentialsTokenSource", () => {
  it("refreshes the token shortly before it expires", async () => {
    let time = 0;
    let n = 0;
    const fetchToken = vi.fn(async () => {
      n++;
      return { accessToken: `token-${n}`, expiresAtMs: time + 120_000 };
    });
    const source = new ClientCredentialsTokenSource(fetchToken, () => time);
    expect(await Promise.all([source.getToken(), source.getToken()])).toEqual([
      "token-1",
      "token-1",
    ]);
    time = 59_999;
    expect(await source.getToken()).toBe("token-1");
    time = 60_000;
    expect(await Promise.all([source.getToken(), source.getToken()])).toEqual([
      "token-2",
      "token-2",
    ]);
    expect(fetchToken).toHaveBeenCalledTimes(2);
  });

  it("retries the refresh after a failure", async () => {
    const fetchToken = vi
      .fn()
      .mockRejectedValueOnce(new Error("boom"))
      .mockResolvedValue({ accessToken: "token", expiresAtMs: 1_000_000 });
    const source = new ClientCredentialsTokenSource(fetchToken, () => 0);
    await expect(source.getToken()).rejects.toThrow("boom");
    expect(await source.getToken()).toBe("token");
  });
});

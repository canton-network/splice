// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiException } from "@canton-network/canton-json-api-v2-openapi";
import {
  cantonErrorCode,
  HttpError,
  isRetryableError,
  retry,
  setRetryFailureListener,
} from "./retry.js";

const apiError = (code: number, body: object = {}) =>
  new ApiException(code, "error", body, {});

describe("isRetryableError", () => {
  it("retries server errors, network errors and our own errors", () => {
    expect(isRetryableError(apiError(503))).toBe(true);
    expect(isRetryableError(new HttpError("url", 500, ""))).toBe(true);
    expect(isRetryableError(new TypeError("fetch failed"))).toBe(true);
    expect(isRetryableError(new Error("No preapproval"))).toBe(true);
  });

  it("retries client errors that depend on the system state", () => {
    for (const code of [404, 408, 409, 425, 429]) {
      expect(isRetryableError(apiError(code))).toBe(true);
      expect(isRetryableError(new HttpError("url", code, ""))).toBe(true);
    }
  });

  it("does not retry other client errors unless Canton marks them as retryable", () => {
    expect(isRetryableError(apiError(400))).toBe(false);
    expect(isRetryableError(apiError(403))).toBe(false);
    expect(isRetryableError(new HttpError("url", 400, ""))).toBe(false);
    expect(isRetryableError(apiError(400, { retryInfo: null }))).toBe(false);
    expect(isRetryableError(apiError(400, { retryInfo: "1 second" }))).toBe(
      true,
    );
  });
});

describe("cantonErrorCode", () => {
  it("extracts the code of Ledger API errors", () => {
    expect(cantonErrorCode(apiError(409, { code: "DUPLICATE_COMMAND" }))).toBe(
      "DUPLICATE_COMMAND",
    );
    expect(cantonErrorCode(apiError(409))).toBeUndefined();
    expect(cantonErrorCode(new Error("x"))).toBeUndefined();
  });
});

describe("retry", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("gives up immediately on permanent errors", async () => {
    const task = vi.fn().mockRejectedValue(apiError(400));
    const onFailure = vi.fn();
    await expect(
      retry("test", task, { maxRetries: 10, onFailure }),
    ).rejects.toThrow();
    expect(task).toHaveBeenCalledTimes(1);
    expect(onFailure).toHaveBeenCalledTimes(1);
  });

  it("retries with jittered exponential backoff and calls onFailure", async () => {
    vi.spyOn(Math, "random").mockReturnValue(0);
    const onFailure = vi.fn();
    const task = vi
      .fn()
      .mockRejectedValueOnce(apiError(503))
      .mockRejectedValueOnce(apiError(503))
      .mockResolvedValue("ok");
    const result = retry("test", task, { initialDelayMs: 1000, onFailure });
    await vi.advanceTimersByTimeAsync(0);
    expect(task).toHaveBeenCalledTimes(1);
    // 50% of 1000ms
    await vi.advanceTimersByTimeAsync(500);
    expect(task).toHaveBeenCalledTimes(2);
    // 50% of 2000ms
    await vi.advanceTimersByTimeAsync(999);
    expect(task).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    await expect(result).resolves.toBe("ok");
    expect(onFailure).toHaveBeenCalledTimes(2);
  });

  it("stops after maxRetries attempts", async () => {
    const task = vi.fn().mockRejectedValue(new Error("boom"));
    const result = retry("test", task, { maxRetries: 3 });
    const assertion = expect(result).rejects.toThrow("boom");
    await vi.runAllTimersAsync();
    await assertion;
    expect(task).toHaveBeenCalledTimes(3);
  });

  it("reports every failed attempt to the failure listener", async () => {
    const listener = vi.fn();
    setRetryFailureListener(listener);
    const task = vi
      .fn()
      .mockRejectedValueOnce(apiError(503))
      .mockRejectedValueOnce(apiError(400));
    const result = retry(`allocate party-1`, task, {
      task: "allocate",
      maxRetries: 3,
    });
    const assertion = expect(result).rejects.toBeInstanceOf(ApiException);
    await vi.runAllTimersAsync();
    await assertion;
    expect(listener.mock.calls).toEqual([
      [{ task: "allocate", transient: true, willRetry: true }],
      [{ task: "allocate", transient: false, willRetry: false }],
    ]);
  });
});

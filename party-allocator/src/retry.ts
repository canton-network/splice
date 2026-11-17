// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ApiException } from "@canton-network/canton-json-api-v2-openapi";
import { logger } from "./logger.js";
import { traceHeaders } from "./tracing.js";

const maxRetryDelayMs = 5000;

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export class HttpError extends Error {
  constructor(
    readonly url: string,
    readonly code: number,
    body: string,
  ) {
    super(`HTTP ${code} from ${url}: ${body}`);
  }
}

function isTransientStatus(code: number): boolean {
  return code >= 500 || code === 429;
}

// 5xx/429 responses and network failures (fetch throws a TypeError) are transient.
export function isTransientError(e: unknown): boolean {
  if (e instanceof ApiException || e instanceof HttpError) {
    return isTransientStatus(e.code);
  }
  return e instanceof TypeError;
}

// The Canton error code of a JSON Ledger API error, e.g. DUPLICATE_COMMAND.
export function cantonErrorCode(e: unknown): string | undefined {
  if (e instanceof ApiException) {
    const code = e.body?.code;
    return typeof code === "string" ? code : undefined;
  }
  return undefined;
}

// Status codes that can succeed on retry once the system state has changed, e.g. a contract
// that is not yet (or no longer) visible or contention.
const retryableClientStatuses = new Set([404, 408, 409, 425, 429]);

// Errors that will fail again when the identical request is retried: 4xx responses other than
// the ones above, unless Canton marks them as retryable through retryInfo.
// Everything else (5xx, network errors, errors thrown by our own code) is retried.
export function isRetryableError(e: unknown): boolean {
  if (e instanceof ApiException) {
    // Canton sends retryInfo: null for errors that are not retryable.
    if (e.body?.retryInfo != null) {
      return true;
    }
    return e.code >= 500 || retryableClientStatuses.has(e.code);
  }
  if (e instanceof HttpError) {
    return e.code >= 500 || retryableClientStatuses.has(e.code);
  }
  return true;
}

export type RetryFailure = {
  // Bounded task name, safe to use as a metric label.
  task: string;
  // 5xx/429 or network error, i.e. a sign of overload.
  transient: boolean;
  // false if retry gives up and rethrows the error.
  willRetry: boolean;
};

let failureListener: ((failure: RetryFailure) => void) | undefined;

// Notified on every failed attempt observed by retry, e.g. to reduce load or record metrics.
export function setRetryFailureListener(
  listener: (failure: RetryFailure) => void,
): void {
  failureListener = listener;
}

export type RetryOptions = {
  // Name of the task for metrics, defaults to the description. Must not contain ids.
  task?: string;
  maxRetries?: number;
  initialDelayMs?: number;
  shouldRetry?: (e: unknown) => boolean;
  // Called on every failed attempt, including the last one, e.g. to invalidate cached inputs of the task.
  onFailure?: (e: unknown) => void;
};

// Retries with exponential backoff starting at initialDelayMs, capped at 5s.
// The actual delay is jittered to 50-100% of that so concurrent retries don't synchronize.
export async function retry<T>(
  description: string,
  task: () => Promise<T>,
  {
    maxRetries = 60,
    initialDelayMs = 1000,
    shouldRetry = isRetryableError,
    onFailure,
    task: taskName = description,
  }: RetryOptions = {},
): Promise<T> {
  let attempt = 1;
  let delayMs = Math.min(initialDelayMs, maxRetryDelayMs);
  // eslint-disable-next-line no-constant-condition
  while (true) {
    try {
      return await task();
    } catch (e: unknown) {
      const willRetry = attempt < maxRetries && shouldRetry(e);
      failureListener?.({
        task: taskName,
        transient: isTransientError(e),
        willRetry,
      });
      onFailure?.(e);
      const errorMessage = e instanceof Error ? e.message : JSON.stringify(e);
      if (willRetry) {
        const jitteredDelayMs = Math.round(
          delayMs * (0.5 + Math.random() * 0.5),
        );
        logger.info(
          `Task ${description} failed after ${attempt} attempts (max ${maxRetries}), retrying in ${jitteredDelayMs}ms: ${errorMessage}`,
        );
        await delay(jitteredDelayMs);
        delayMs = Math.min(delayMs * 2, maxRetryDelayMs);
      } else {
        logger.error(
          `Task ${description} failed after ${attempt} attempts, giving up: ${errorMessage}`,
        );
        throw e;
      }
    }
    attempt++;
  }
}

// fetch that propagates the trace context and fails on non-2xx responses.
export async function fetchJson<T>(
  url: string,
  init?: RequestInit,
): Promise<T> {
  const headers = {
    ...(init?.headers as Record<string, string>),
    ...traceHeaders(),
  };
  logger.debug(`[Request] ${url} traceparent=${headers.traceparent}`);
  const response = await fetch(url, { ...init, headers });
  logger.debug(`[Response] ${url} ${response.status}`);
  if (!response.ok) {
    throw new HttpError(url, response.status, await response.text());
  }
  return (await response.json()) as T;
}

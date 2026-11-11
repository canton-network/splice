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

let transientErrorListener: (() => void) | undefined;

// Notified on every transient error observed by retry, e.g. to reduce load.
export function setTransientErrorListener(listener: () => void): void {
  transientErrorListener = listener;
}

export type RetryOptions = {
  maxRetries?: number;
  initialDelayMs?: number;
  shouldRetry?: (e: unknown) => boolean;
};

// Retries with exponential backoff starting at initialDelayMs, capped at 5s.
export async function retry<T>(
  description: string,
  task: () => Promise<T>,
  {
    maxRetries = 60,
    initialDelayMs = 1000,
    shouldRetry = () => true,
  }: RetryOptions = {},
): Promise<T> {
  let attempt = 1;
  let delayMs = Math.min(initialDelayMs, maxRetryDelayMs);
  // eslint-disable-next-line no-constant-condition
  while (true) {
    try {
      return await task();
    } catch (e: unknown) {
      if (isTransientError(e)) {
        transientErrorListener?.();
      }
      const errorMessage = e instanceof Error ? e.message : JSON.stringify(e);
      if (attempt < maxRetries && shouldRetry(e)) {
        logger.info(
          `Task ${description} failed after ${attempt} attempts (max ${maxRetries}), retrying in ${delayMs}ms: ${errorMessage}`,
        );
        await delay(delayMs);
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

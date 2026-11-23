// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { AsyncLocalStorage } from "node:async_hooks";
import * as crypto from "node:crypto";

const traceStorage = new AsyncLocalStorage<{ traceId: string }>();

function newTraceId(): string {
  return crypto.randomBytes(16).toString("hex");
}

function newSpanId(): string {
  return crypto.randomBytes(8).toString("hex");
}

// Runs the task in a new trace; all logs and requests within it share the trace id.
export function withNewTrace<T>(task: () => Promise<T>): Promise<T> {
  return traceStorage.run({ traceId: newTraceId() }, task);
}

export function currentTraceId(): string | undefined {
  return traceStorage.getStore()?.traceId;
}

// W3C trace context header (https://www.w3.org/TR/trace-context/) with a new span id for each request.
// Outside of a trace, each request gets its own trace id.
export function traceparentHeader(): string {
  return `00-${currentTraceId() ?? newTraceId()}-${newSpanId()}-01`;
}

export function traceHeaders(): Record<string, string> {
  return { traceparent: traceparentHeader() };
}

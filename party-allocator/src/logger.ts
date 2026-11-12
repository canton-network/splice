// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import pino from "pino";
import { currentTraceId } from "./tracing.js";

const isoTime = () => `,"@timestamp":"${new Date(Date.now()).toISOString()}"`;

// Log format tweaked to parse well by fluentbit/gcp.
export const logger = pino({
  // Tests set this to silent to keep the output clean.
  level: process.env.LOG_LEVEL ?? "debug",
  base: null,
  formatters: {
    level: (label) => ({ level: label }),
  },
  timestamp: isoTime,
  messageKey: "message",
  mixin: () => {
    const traceId = currentTraceId();
    return traceId ? { "trace-id": traceId } : {};
  },
});

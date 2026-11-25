// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// The legacy BigQuery max_staleness check (bigQuery.ts) fails with an
// error that contains this text, and the alert policy for it
// (observability) matches the logged error on the same text.
export function legacyMaxStalenessErrorTag(cluster: string): string {
  return `splice-legacy-bq-max-staleness-below-safe cluster=${cluster}`;
}

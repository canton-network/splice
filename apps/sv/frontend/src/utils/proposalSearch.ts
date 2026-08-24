// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/** MSW mock CIDs use this length with non-ledger prefixes (`10…`, `99…`). */
const MOCK_TEST_CONTRACT_ID_LENGTH = 138;

export const CONTRACT_ID_VALIDATION_MESSAGE = 'Enter a valid contract ID.';

/** Bounds mirror LfValue.ContractId.fromString (Value.scala) + even hex for Bytes.fromString. */
const V1_MIN_LENGTH = 66;
const V1_MAX_LENGTH = 254;
const V2_MIN_LENGTH = 26;
const V2_MAX_LENGTH = 92;

function isHexChar(code: number): boolean {
  return (code >= 48 && code <= 57) || (code >= 97 && code <= 102) || (code >= 65 && code <= 70);
}

function isPlausibleContractId(value: string): boolean {
  const len = value.length;
  if (len === 0 || (len & 1) !== 0 || len < V2_MIN_LENGTH || len > V1_MAX_LENGTH) {
    return false;
  }

  for (let i = 0; i < len; i++) {
    if (!isHexChar(value.charCodeAt(i))) {
      return false;
    }
  }

  if (value.charCodeAt(0) === 48 && value.charCodeAt(1) === 48) {
    return len >= V1_MIN_LENGTH && len <= V1_MAX_LENGTH;
  }
  if (value.charCodeAt(0) === 48 && value.charCodeAt(1) === 49) {
    return len >= V2_MIN_LENGTH && len <= V2_MAX_LENGTH;
  }
  return len === MOCK_TEST_CONTRACT_ID_LENGTH;
}

export function isValidContractId(value: string): boolean {
  return isPlausibleContractId(value.trim());
}

function normalizeContractIdQuery(query: string): string | null {
  const trimmed = query.trim();
  if (!trimmed || !isPlausibleContractId(trimmed)) {
    return null;
  }
  return trimmed.toLowerCase();
}

function matchesNormalizedContractId(
  normalizedQuery: string,
  contractId: string | null | undefined
): boolean {
  return typeof contractId === 'string' && contractId.toLowerCase() === normalizedQuery;
}

export function filterByContractId<T extends { contractId: unknown }>(
  items: T[],
  query: string | null | undefined
): T[] {
  const normalizedQuery = query == null ? null : normalizeContractIdQuery(query);
  if (normalizedQuery === null) {
    return [];
  }
  return items.filter(item =>
    matchesNormalizedContractId(normalizedQuery, item.contractId as string)
  );
}

export function findByContractId<T>(
  items: T[],
  query: string,
  getContractId: (item: T) => string | null | undefined
): T | undefined {
  const normalizedQuery = normalizeContractIdQuery(query);
  if (normalizedQuery === null) {
    return undefined;
  }
  return items.find(item => matchesNormalizedContractId(normalizedQuery, getContractId(item)));
}

export function shouldContinueVoteHistorySearch<T>(
  query: string,
  results: T[],
  getContractId: (item: T) => string | null | undefined
): boolean {
  const normalizedQuery = normalizeContractIdQuery(query);
  if (normalizedQuery === null) {
    return false;
  }
  return !results.some(item => matchesNormalizedContractId(normalizedQuery, getContractId(item)));
}

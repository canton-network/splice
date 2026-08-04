// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type { DsoRules_CloseVoteRequestResult } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

export const CONTRACT_ID_HEX_LENGTH = 138;

const CONTRACT_ID_PATTERN = new RegExp(`^[0-9a-fA-F]{${CONTRACT_ID_HEX_LENGTH}}$`);

export function isValidContractId(value: string): boolean {
  return CONTRACT_ID_PATTERN.test(value.trim());
}

function matchesContractId(query: string, contractId: string | null | undefined): boolean {
  return typeof contractId === 'string' && contractId.toLowerCase() === query.trim().toLowerCase();
}

export function filterByContractId<T extends { contractId: unknown }>(
  items: T[],
  query: string | null | undefined
): T[] {
  const trimmed = query?.trim();
  if (!trimmed) {
    return items;
  }
  if (!isValidContractId(trimmed)) {
    return [];
  }
  return items.filter(item => matchesContractId(trimmed, item.contractId as string));
}

export function findCloseVoteResultByContractId(
  results: DsoRules_CloseVoteRequestResult[],
  contractId: string
): DsoRules_CloseVoteRequestResult | undefined {
  if (!isValidContractId(contractId)) {
    return undefined;
  }
  return results.find(result => matchesContractId(contractId, result.request.trackingCid));
}

export function shouldContinueVoteHistorySearch(
  query: string,
  results: DsoRules_CloseVoteRequestResult[]
): boolean {
  if (!isValidContractId(query)) {
    return false;
  }
  return findCloseVoteResultByContractId(results, query) === undefined;
}

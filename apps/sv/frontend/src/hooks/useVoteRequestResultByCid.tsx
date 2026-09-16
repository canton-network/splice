// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ContractId } from '@daml/types';
import {
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { Contract } from '@canton-network/splice-common-frontend-utils';

import { usePaginatedVoteRequestResultsByContractId } from './useListVoteRequests';
import { useVoteRequest } from './useVoteRequest';
import { findByContractId } from '../utils/proposalSearch';

interface UseVoteRequestResultByCidResult {
  voteRequest: Contract<VoteRequest> | undefined;
  voteResult: DsoRules_CloseVoteRequestResult | undefined;
  hasVoteRequest: boolean;
  hasVoteResult: boolean;
  isPending: boolean;
  isComplete: boolean;
}

//TODO(#1208): Move this logic to the backend and expose via a new endpoint
export function useVoteRequestResultByCid(
  contractId: ContractId<VoteRequest>
): UseVoteRequestResultByCidResult {
  const voteRequestQuery = useVoteRequest(contractId, false, false);

  const hasVoteRequest = voteRequestQuery.isSuccess && voteRequestQuery.data != null;

  const needsClosedVoteFetch =
    (voteRequestQuery.isSuccess || voteRequestQuery.isError) && !hasVoteRequest;

  const closedVoteResults = usePaginatedVoteRequestResultsByContractId(needsClosedVoteFetch, {
    contractId,
  });

  const voteResult = findByContractId(
    closedVoteResults.results,
    contractId,
    result => result.request.trackingCid
  );
  const hasVoteResult = voteResult !== undefined;

  const isPending =
    voteRequestQuery.isPending ||
    (needsClosedVoteFetch && !closedVoteResults.isComplete && !hasVoteResult);

  const isComplete =
    (voteRequestQuery.isSuccess || voteRequestQuery.isError) &&
    (!needsClosedVoteFetch || closedVoteResults.isComplete || hasVoteResult);

  const voteRequest = voteRequestQuery.data;

  return {
    voteRequest,
    voteResult,
    hasVoteRequest,
    hasVoteResult,
    isPending,
    isComplete,
  };
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { List } from '@daml/types';
import {
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { Contract, PollingStrategy } from '@canton-network/splice-common-frontend-utils';
import { type UseQueryResult, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { useConfigPollInterval } from '../utils';
import { shouldContinueVoteHistorySearch } from '../utils/proposalSearch';

const PAGINATED_VOTE_RESULTS_QUERY_KEY = 'paginatedVoteRequestResults';
const PAGINATED_VOTE_RESULTS_PAGE_SIZE = 500;

export type ListVoteRequestResultParams = {
  actionName?: string;
  accepted?: boolean;
  requester?: string;
  effectiveFrom?: string;
  effectiveTo?: string;
};

export const useListDsoRulesVoteRequests = (
  refetchInterval?: number
): UseQueryResult<Contract<VoteRequest>[]> => {
  const { listDsoRulesVoteRequests } = useSvAdminClient();
  const defaultRefetchInterval = useConfigPollInterval();

  return useQuery({
    queryKey: ['listDsoRulesVoteRequests'],
    queryFn: async () => {
      const { dso_rules_vote_requests } = await listDsoRulesVoteRequests();
      return dso_rules_vote_requests.map(c => Contract.decodeOpenAPI(c, VoteRequest));
    },
    refetchInterval: refetchInterval ?? defaultRefetchInterval,
  });
};

export const useListVoteRequestResult = (
  query: ListVoteRequestResultParams,
  limit: number = 10,
  retry: boolean = true
): UseQueryResult<DsoRules_CloseVoteRequestResult[]> => {
  const { listVoteRequestResults } = useSvAdminClient();
  return useQuery({
    queryKey: [
      'listVoteRequestResults',
      DsoRules_CloseVoteRequestResult,
      limit,
      query.actionName,
      query.accepted,
      query.requester,
      query.effectiveFrom,
      query.effectiveTo,
    ],
    queryFn: async () => {
      const { dso_rules_vote_results } = await listVoteRequestResults(
        limit,
        query.actionName,
        query.requester,
        query.effectiveFrom,
        query.effectiveTo,
        query.accepted
      );
      return List(DsoRules_CloseVoteRequestResult).decoder.runWithException(dso_rules_vote_results);
    },
    retry,
  });
};

function usePaginatedVoteRequestResultsBucket(
  contractId: string,
  accepted: boolean,
  enabled: boolean,
  shouldContinueRef: React.MutableRefObject<() => boolean>
) {
  const { listVoteRequestResults } = useSvAdminClient();
  const queryKey = [
    PAGINATED_VOTE_RESULTS_QUERY_KEY,
    contractId,
    accepted,
    PAGINATED_VOTE_RESULTS_PAGE_SIZE,
  ] as const;

  const {
    hasNextPage,
    isFetchingNextPage,
    isPending,
    dataUpdatedAt,
    fetchNextPage,
    data,
    isSuccess,
  } = useInfiniteQuery({
    queryKey,
    queryFn: async ({ pageParam }) => {
      const response = await listVoteRequestResults(
        PAGINATED_VOTE_RESULTS_PAGE_SIZE,
        undefined,
        undefined,
        undefined,
        undefined,
        accepted,
        pageParam ?? undefined
      );

      return {
        results: List(DsoRules_CloseVoteRequestResult).decoder.runWithException(
          response.dso_rules_vote_results
        ),
        nextPageToken: response.next_page_token,
      };
    },
    initialPageParam: null as number | null,
    getNextPageParam: lastPage => lastPage?.nextPageToken ?? null,
    enabled,
    refetchInterval: PollingStrategy.NONE,
    refetchOnWindowFocus: false,
  });

  useEffect(() => {
    if (
      !enabled ||
      !shouldContinueRef.current() ||
      !hasNextPage ||
      isFetchingNextPage ||
      isPending
    ) {
      return;
    }
    void fetchNextPage();
  }, [
    enabled,
    hasNextPage,
    isFetchingNextPage,
    isPending,
    dataUpdatedAt,
    fetchNextPage,
    shouldContinueRef,
  ]);

  const results = data?.pages.flatMap(page => page.results) ?? [];

  return {
    results,
    isNaturallyComplete: isSuccess && !hasNextPage,
    hasFirstPage: (data?.pages.length ?? 0) > 0,
  };
}

export function usePaginatedVoteRequestResultsByContractId(
  enabled: boolean,
  { contractId = '' }: { contractId?: string } = {}
): {
  results: DsoRules_CloseVoteRequestResult[];
  isComplete: boolean;
} {
  const shouldContinueRef = useRef<() => boolean>(() => false);
  const fetchDisabledRef = useRef(false);
  const previousLookupRef = useRef({ enabled, contractId });

  if (
    previousLookupRef.current.enabled !== enabled ||
    previousLookupRef.current.contractId !== contractId
  ) {
    fetchDisabledRef.current = false;
    previousLookupRef.current = { enabled, contractId };
  }

  const bucketEnabled = enabled && !fetchDisabledRef.current;

  const accepted = usePaginatedVoteRequestResultsBucket(
    contractId,
    true,
    bucketEnabled,
    shouldContinueRef
  );
  const rejected = usePaginatedVoteRequestResultsBucket(
    contractId,
    false,
    bucketEnabled,
    shouldContinueRef
  );
  const results = enabled ? [...accepted.results, ...rejected.results] : [];

  const shouldContinue =
    bucketEnabled &&
    shouldContinueVoteHistorySearch(contractId, results, result => result.request.trackingCid);
  shouldContinueRef.current = () => shouldContinue;

  const isComplete =
    (accepted.isNaturallyComplete && rejected.isNaturallyComplete) ||
    (!shouldContinue && accepted.hasFirstPage && rejected.hasFirstPage);

  if (isComplete) {
    fetchDisabledRef.current = true;
  }

  return {
    results,
    isComplete,
  };
}

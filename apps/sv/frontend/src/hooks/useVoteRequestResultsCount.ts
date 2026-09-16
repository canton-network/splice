// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useVoteRequestResultsCount = (): UseQueryResult<number> => {
  const { countVoteRequestResults } = useSvAdminClient();
  return useQuery({
    queryKey: ['voteRequestResultsCount'],
    queryFn: async () => {
      const [effective, notAccepted] = await Promise.all([
        countVoteRequestResults(true, new Date().toISOString()),
        countVoteRequestResults(false),
      ]);
      return effective.count + notAccepted.count;
    },
  });
};

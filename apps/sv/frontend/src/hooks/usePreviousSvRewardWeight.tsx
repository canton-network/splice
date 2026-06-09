// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useQuery } from '@tanstack/react-query';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export interface PreviousSvRewardWeightResult {
  weight: string | undefined;
  isPending: boolean;
}

export function usePreviousSvRewardWeight(
  svParty: string | undefined,
  effectiveBefore: string | undefined
): PreviousSvRewardWeightResult {
  const { getPreviousSvRewardWeight } = useSvAdminClient();
  const query = useQuery({
    queryKey: ['getPreviousSvRewardWeight', svParty, effectiveBefore],
    queryFn: async () => {
      const { rewardWeight } = await getPreviousSvRewardWeight(svParty!, effectiveBefore);
      return rewardWeight ?? null;
    },
    enabled: !!svParty,
    retry: false,
  });

  return { weight: query.data ?? undefined, isPending: !!svParty && query.isPending };
}

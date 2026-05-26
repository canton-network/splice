// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useSvRewardWeightBefore = (
  svParty: string | undefined,
  before: string | undefined
): UseQueryResult<number | null> => {
  const { lookupSvRewardWeightBefore } = useSvAdminClient();
  return useQuery({
    queryKey: ['lookupSvRewardWeightBefore', svParty, before],
    queryFn: () => lookupSvRewardWeightBefore(svParty!, before!),
    enabled: !!svParty && !!before,
    staleTime: Infinity,
  });
};

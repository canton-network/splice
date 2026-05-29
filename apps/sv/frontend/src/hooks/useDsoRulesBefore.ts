// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { DsoRules } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useDsoRulesBefore = (
  before: string | undefined
): UseQueryResult<Contract<DsoRules> | undefined> => {
  const { lookupDsoRulesBefore } = useSvAdminClient();
  return useQuery({
    queryKey: ['lookupDsoRulesBefore', before],
    queryFn: async () => {
      const response = await lookupDsoRulesBefore(before!);
      return response.dso_rules ? Contract.decodeOpenAPI(response.dso_rules, DsoRules) : undefined;
    },
    enabled: !!before,
    staleTime: Infinity,
  });
};

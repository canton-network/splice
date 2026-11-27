// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { clusterProdLike, isMainNet } from '../config';
import { clusterYamlConfig } from '../config/config';
import { DEFAULT_AUDIENCE } from './audiences';

const AudienceConfigSchema = z.union([
  z.strictObject({
    type: z.literal('namespaced-managed'),
  }),
  z.strictObject({
    type: z.literal('unmanaged'),
    audience: z.string(),
  }),
]);

const Auth0ConfigSchema = z.strictObject({
  audiences: z.strictObject({
    ledgerApi: AudienceConfigSchema,
    validatorApi: AudienceConfigSchema,
    svAppApi: AudienceConfigSchema,
  }),
});

const Auth0RootConfigSchema = z.object({
  auth0: Auth0ConfigSchema
    // TODO(): remove these defaults when we get config for the respective clusters.
    .default(() => {
      if (isMainNet) {
        return {
          audiences: {
            ledgerApi: {
              type: 'unmanaged' as const,
              audience: 'https://ledger_api.main.digitalasset.com',
            },
            validatorApi: {
              type: 'unmanaged' as const,
              audience: 'https://validator.main.digitalasset.com',
            },
            svAppApi: { type: 'unmanaged' as const, audience: 'https://sv.main.digitalasset.com' },
          },
        };
      } else if (clusterProdLike) {
        return {
          audiences: {
            ledgerApi: { type: 'namespaced-managed' as const },
            validatorApi: { type: 'namespaced-managed' as const },
            svAppApi: { type: 'namespaced-managed' as const },
          },
        };
      } else {
        return {
          audiences: {
            ledgerApi: { type: 'unmanaged' as const, audience: DEFAULT_AUDIENCE },
            validatorApi: { type: 'unmanaged' as const, audience: DEFAULT_AUDIENCE },
            svAppApi: { type: 'unmanaged' as const, audience: DEFAULT_AUDIENCE },
          },
        };
      }
    }),
});

export type Auth0RootConfig = z.infer<typeof Auth0RootConfigSchema>;

export const auth0Config = Auth0RootConfigSchema.parse(clusterYamlConfig).auth0;

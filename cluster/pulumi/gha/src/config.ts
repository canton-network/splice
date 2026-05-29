// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { SingleResourceSchema } from '@lfdecentralizedtrust/splice-pulumi-common';
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import { spliceEnvConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/envConfig';
import { GcpProject } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/gcpConfig';
import util from 'node:util';
import { z } from 'zod';

const GhaConfigSchema = z.object({
  gha: z.object({
    githubOrg: z.string(),
    githubRepos: z.array(z.string()),
    // these a Splice versions
    runnerVersion: z.string(),
    runnerHookVersion: z.string(),
    // this is a https://github.com/actions/actions-runner-controller version
    runnerScaleSetVersion: z.string(),
    workPvcSize: z.string().default('20Gi'),
    runnerSpecs: z
      .array(
        z.object({
          name: z.enum(['tiny', 'x-small', 'small', 'medium', 'large', 'x-large']),
          k8s: z.boolean(),
          docker: z.boolean(),
          resources: z.object({
            limits: SingleResourceSchema,
            requests: SingleResourceSchema,
          }),
        })
      )
      .default([]),
    mainnetHistoryDumpsUser: z
      .object({
        wifProjectNumber: z.string(),
        wifPoolId: z.string(),
        // GitHub repos (full "org/name") allowed to impersonate the SA via WIF.
        githubRepositories: z.array(z.string()).min(1),
      })
      .optional(),
  }),
});

export type Config = z.infer<typeof GhaConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = GhaConfigSchema.parse(clusterYamlConfig);

console.error(
  `Loaded GHA config: ${util.inspect(fullConfig, {
    depth: null,
    maxStringLength: null,
  })}`
);

export const ghaConfig = fullConfig.gha;

export const isSpliceCluster =
  GcpProject === 'da-cn-splice' || spliceEnvConfig.optionalEnv('GCP_CLUSTER_BASENAME') === 'splice';

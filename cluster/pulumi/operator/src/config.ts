// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { config, GitReferenceSchema } from '@canton-network/splice-pulumi-common';
import { clusterSubConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import { z } from 'zod';

export const OperatorDeploymentConfigSchema = z.object({
  reference: GitReferenceSchema,
  flux: z
    .object({
      alertSlackChannel: z
        .string()
        .default(() => config.requireEnv('SLACK_ALERT_NOTIFICATION_CHANNEL_FULL_NAME')),
    })
    .prefault({}),
});

export type Config = z.infer<typeof OperatorDeploymentConfigSchema>;

export const operatorDeploymentConfig = OperatorDeploymentConfigSchema.parse(
  clusterSubConfig('operatorDeployment')
);
export const fluxConfig = operatorDeploymentConfig.flux;

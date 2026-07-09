// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterSubConfig, config } from '@canton-network/splice-pulumi-common';
import { z } from 'zod';

const OperatorFluxConfigSchema = z.object({
  flux: z
    .object({
      alertSlackChannel: z
        .string()
        .default(() => config.requireEnv('SLACK_ALERT_NOTIFICATION_CHANNEL_FULL_NAME')),
    })
    .prefault({}),
});

export const operatorFluxConfig = OperatorFluxConfigSchema.parse(clusterSubConfig('operator'));

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { K8sResourceSchema } from './configSchema';

export const TopologyKeyMonitorConfigSchema = z
  .object({
    enable: z.boolean(),
    keys: z.array(z.string()).default([]),
    pollIntervalSeconds: z.number().int().positive().default(60),
    chartVersion: z.string().optional(),
    resources: K8sResourceSchema,
  })
  .strict();

export type TopologyKeyMonitorConfig = z.infer<typeof TopologyKeyMonitorConfigSchema>;

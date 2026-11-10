// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { clusterYamlConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import util from 'node:util';
import { z } from 'zod';

import { K8sResourceSchema } from './configSchema';

export const LoadTesterAdaptiveScenarioConfigSchema = z.object({
  maxVUs: z.number().default(50),
  minVUs: z.number().default(0),
  enabled: z.boolean().default(false),
  scaleDownStep: z.number().default(5),
  scaleUpStep: z.number().default(2),
  windowStartUTC: z.string().default('03:00'),
  windowDurationMinutes: z.number().default(120),
});

export const LoadTesterConfigSchema = z.object({
  loadTester: z
    .object({
      enable: z.boolean(),
      chartVersion: z.string().optional(),
      minRate: z.number().default(0.9),
      iterationsPerMinute: z.number().default(60),
      maxVUs: z.number().optional(),
      adaptiveScenario: LoadTesterAdaptiveScenarioConfigSchema.prefault({}),
      usersPerValidator: z.number().default(10),
      // Featured app rights of the users of each validator, see load-tester/src/settings.ts
      featuredAppRights: z
        .array(
          z.object({
            kind: z.enum(['basic', 'asset_moving', 'asset']).optional(),
            weight: z.number().optional(),
            parties: z.array(z.number()).optional(),
          })
        )
        // Keep in sync with the default in load-tester/src/settings.ts
        .default([
          {},
          { kind: 'asset_moving', weight: 2, parties: [3] },
          { kind: 'asset', weight: 0.5 },
        ]),
      resources: K8sResourceSchema.optional(),
    })
    .optional(),
});

export const loadTesterConfig = LoadTesterConfigSchema.parse(clusterYamlConfig).loadTester;

void pulumi.log.debug(
  `Loaded load tester configuration ${util.inspect(loadTesterConfig, {
    depth: null,
    maxStringLength: null,
  })}`
);

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
      // Number of users per validator with a default featured app right.
      // Superseded by featuredAppRights, cannot be combined with it.
      featuredUsersPerValidator: z.number().optional(),
      // Featured app rights of the users of each validator, see load-tester/src/settings.ts
      featuredAppRights: z
        .array(
          z.object({
            kind: z.enum(['basic', 'asset_moving', 'asset']).optional(),
            weight: z.number().optional(),
            parties: z.array(z.number()).optional(),
          })
        )
        .optional(),
      resources: K8sResourceSchema.optional(),
    })
    .refine(c => c.featuredUsersPerValidator === undefined || c.featuredAppRights === undefined, {
      message: 'Set at most one of featuredUsersPerValidator and featuredAppRights',
    })
    .transform(({ featuredUsersPerValidator, featuredAppRights, ...rest }) => ({
      ...rest,
      featuredAppRights:
        featuredAppRights ??
        (featuredUsersPerValidator !== undefined
          ? Array.from({ length: featuredUsersPerValidator }, () => ({}))
          : // One right of each kind: user 0 gets a default (basic) right, user 1 an asset-moving
            // right with user 3 as an ops party, and user 2 an asset right with a non-default weight.
            [
              {},
              { kind: 'asset_moving' as const, weight: 2, parties: [3] },
              { kind: 'asset' as const, weight: 0.5 },
            ]),
    }))
    .optional(),
});

export const loadTesterConfig = LoadTesterConfigSchema.parse(clusterYamlConfig).loadTester;

void pulumi.log.debug(
  `Loaded load tester configuration ${util.inspect(loadTesterConfig, {
    depth: null,
    maxStringLength: null,
  })}`
);

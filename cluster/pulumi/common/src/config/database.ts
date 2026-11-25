// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { merge } from 'lodash';
import { z } from 'zod';

import { spliceConfig } from './config';

export const CloudSqlConfigSchema = z.object({
  enabled: z.boolean(),
  // Docs on cloudsql maintenance windows: https://cloud.google.com/sql/docs/postgres/set-maintenance-window
  maintenanceWindow: z
    .object({
      day: z.number().min(1).max(7).default(2), // 1 (Monday) to 7 (Sunday)
      hour: z.number().min(0).max(23).default(8), // 24-hour format UTC
    })
    .default({ day: 2, hour: 8 }),
  protected: z.boolean(),
  tier: z.string(),
  enterprisePlus: z.boolean(),
  flags: z.record(z.string(), z.string()).default({}),
  enableAutoBackups: z.boolean().default(true),
  backupStartTime: z.string().optional(),
  databaseVersion: z.string().default('POSTGRES_18'),
});
export type CloudSqlConfig = z.infer<typeof CloudSqlConfigSchema>;

export const defaultPostgresImage =
  'postgres:18.7-trixie@sha256:2ea8630df176ad090ec92136dee850397c5af8781e4bcf2e4162593691c61bbc';

export const SplicePostgresSchema = z.object({
  postgresImage: z.string(),
});
export type SplicePostgresConfig = z.infer<typeof SplicePostgresSchema>;

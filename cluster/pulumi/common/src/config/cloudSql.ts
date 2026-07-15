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
  // https://cloud.google.com/sql/docs/mysql/backup-recovery/backups#retained-backups
  // controls the number of automated gcp sql backups to retain
  backupsToRetain: z.number().optional(),
  databaseVersion: z.string().default('POSTGRES_14'),
});
export type CloudSqlConfig = z.infer<typeof CloudSqlConfigSchema>;

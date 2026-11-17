// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as path from 'path';
import { loadYamlFromFile } from '@canton-network/splice-pulumi-common/src/utils';
import { z } from 'zod';

import {
  FlywayMigrationSchema,
  type FlywayMigrationToWaitFor,
  flywayMigrationVersion,
} from './bigQueryFlywayMigration';

// The format of bigquery-replicated-tables.yaml; its header comment
// describes the fields.

const AppendModeSchema = z.enum(['watermark', 'newKeys']);

export type AppendMode = z.infer<typeof AppendModeSchema>;

const ColumnConfigSchema = z.union([
  z.string(),
  z.strictObject({ name: z.string(), since: FlywayMigrationSchema }),
]);

const ReplicatedTableConfigSchema = z.strictObject({
  since: FlywayMigrationSchema.optional(),
  primaryKey: z.string(),
  datePartitionColumn: z.string(),
  timeType: z.enum(['micros', 'datastream_metadata']),
  appendMode: AppendModeSchema,
  columns: z.array(ColumnConfigSchema).nonempty(),
});

export const ReplicatedTablesConfigSchema = z.record(z.string(), ReplicatedTableConfigSchema);

export type ReplicatedTablesConfig = z.infer<typeof ReplicatedTablesConfigSchema>;

export const replicatedTablesFile = path.join(__dirname, 'bigquery-replicated-tables.yaml');

export function loadReplicatedTablesConfig(
  file: string = replicatedTablesFile
): ReplicatedTablesConfig {
  return ReplicatedTablesConfigSchema.parse(loadYamlFromFile(file));
}

// A table replicated once `flywayMigrationToWaitFor` has been applied
export interface ReplicatedTableConfig {
  primaryKey: string;
  datePartitionColumn: string;
  timeType: 'micros' | 'datastream_metadata';
  appendMode: AppendMode;
  // the columns copied to production, datastream_metadata last
  columns: string[];
  // those of `columns` with a `since`, which a production table
  // created before it lacks
  sinceColumns: string[];
  // whether the columns depend on `flywayMigrationToWaitFor`, so
  // the production table may lack some of them; only then is its
  // schema checked and fixed
  migratesSchema: boolean;
}

export type ReplicatedTables = Record<string, ReplicatedTableConfig>;

// `xs` with `f` applied to every value, without the keys for which
// `f` returns undefined
function filterMapValues<A, B>(
  xs: Record<string, A>,
  f: (a: A) => B | undefined
): Record<string, B> {
  return Object.fromEntries(
    Object.entries<A>(xs).flatMap(([key, a]) => {
      const b = f(a);
      return b === undefined ? [] : [[key, b]];
    })
  );
}

// What tables from Scan to replicate to BigQuery, and their columns.
// All should exist once `flywayMigrationToWaitFor` has been applied,
// so the publication, both Datastreams and the scheduled queries always
// agree on the replicated tables.
export function replicatedTablesFor(
  flywayMigrationToWaitFor: FlywayMigrationToWaitFor,
  config: ReplicatedTablesConfig = loadReplicatedTablesConfig()
): ReplicatedTables {
  const applied = (since: FlywayMigrationToWaitFor | undefined) =>
    since === undefined ||
    flywayMigrationVersion(since) <= flywayMigrationVersion(flywayMigrationToWaitFor);
  return filterMapValues(
    config,
    ({ since, primaryKey, datePartitionColumn, timeType, appendMode, columns }) =>
      applied(since)
        ? {
            primaryKey,
            datePartitionColumn,
            timeType,
            appendMode,
            columns: [
              ...columns.flatMap(column =>
                typeof column === 'string' ? [column] : applied(column.since) ? [column.name] : []
              ),
              'datastream_metadata',
            ],
            sinceColumns: columns.flatMap(column =>
              typeof column !== 'string' && applied(column.since) ? [column.name] : []
            ),
            migratesSchema: columns.some(column => typeof column !== 'string'),
          }
        : undefined
  );
}

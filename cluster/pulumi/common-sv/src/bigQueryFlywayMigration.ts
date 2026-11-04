// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'fs';
import { SPLICE_ROOT } from '@canton-network/splice-pulumi-common/src/utils';
import { z } from 'zod';

const flywayMigrationsToWaitFor = [
  'V068__app_activity_record_meta.sql',
  'V074__string_interning.sql',
] as const;

export function checkFlywayMigrationsExist(dir: string, migrations: readonly string[]): void {
  const missing = migrations.filter(migration => !fs.existsSync(`${dir}/${migration}`));
  if (missing.length > 0) {
    throw new Error(`Flyway migrations allowed in scanApp.bigQuery are not in ${dir}: ${missing}`);
  }
}

checkFlywayMigrationsExist(
  `${SPLICE_ROOT}/apps/common/src/main/resources/db/migration/canton-network/postgres/stable`,
  flywayMigrationsToWaitFor
);

export const FlywayMigrationToWaitForSchema = z
  .enum(flywayMigrationsToWaitFor)
  .default('V068__app_activity_record_meta.sql');

export type FlywayMigrationToWaitFor = z.infer<typeof FlywayMigrationToWaitForSchema>;

// V074__string_interning.sql creates interned_strings; earlier
// migrations don't have it
const internedStringsFlywayVersion = 74;

export function flywayMigrationVersion(migration: string): number {
  const match = /^V(\d+)__/.exec(migration);
  if (!match) {
    throw new Error(`Not a versioned Flyway migration: ${migration}`);
  }
  return parseInt(match[1], 10);
}

// whether the scan DB is guaranteed to have interned_strings once
// `migration` has been applied
export function migrationIncludesInternedStrings(migration: FlywayMigrationToWaitFor): boolean {
  return flywayMigrationVersion(migration) >= internedStringsFlywayVersion;
}

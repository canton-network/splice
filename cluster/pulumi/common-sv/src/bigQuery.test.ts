// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as command from '@pulumi/command';
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs';
import * as os from 'os';
import { collectResources } from '@canton-network/splice-pulumi-common/src/test';
import { beforeAll, describe, expect, test } from '@jest/globals';

import type { FlywayMigrationToWaitFor } from './bigQueryFlywayMigration';
import type { ScanBigQueryConfig } from './singleSvConfig';

/*eslint no-process-env: "off"*/
// use the mock cluster's environment, as `make cluster/pulumi/test` does for dump-config
process.env.CN_PULUMI_LOAD_ENV_CONFIG_FILE = 'true';
process.env.GCP_CLUSTER_BASENAME = 'mock';
// Force this to the splice deployment dir as mock is always in there.
process.env.DEPLOYMENT_DIR = `${process.env.SPLICE_ROOT}/cluster/deployment`;

const legacyDatasetId = 'mock_scan';

async function installBigQuery(
  enableLegacyDatastream: boolean,
  enableStagProdDatastream: boolean,
  flywayMigrationToWaitFor: FlywayMigrationToWaitFor
): Promise<pulumi.Resource[]> {
  // imported here so the environment above is in place when the config modules load
  const { configureScanBigQuery } = await import('./bigQuery');
  const [, resources] = await collectResources(() =>
    configureScanBigQuery({
      namespace: { ns: new k8s.core.v1.Namespace('sv-1', {}), logicalName: 'sv-1' },
      bigQueryConfig: {
        dataset: legacyDatasetId,
        prefix: 'mock',
        enableLegacyDatastream,
        enableStagProdDatastream,
        legacyDesiredState: 'RUNNING',
        stagProdDesiredState: 'RUNNING',
        retentionPeriodSeconds: 7 * 24 * 3600,
        flywayMigrationToWaitFor,
      } satisfies ScanBigQueryConfig,
      scanReference: { type: 'external', databaseInstanceNamePrefix: 'sv-1-cn-apps-pg' },
    })
  );
  return resources;
}

function outputValue<T>(output: pulumi.Output<T>): Promise<T> {
  return new Promise(resolve => output.apply(resolve));
}

async function legacyPath(resources: pulumi.Resource[]) {
  const ids = <R extends pulumi.Resource>(
    isInstance: (r: unknown) => r is R,
    id: (r: R) => pulumi.Output<string | undefined>
  ) => Promise.all(resources.filter(isInstance).map(r => outputValue(id(r))));
  const datasetIds = await ids(gcp.bigquery.Dataset.isInstance, r => r.datasetId);
  const streamIds = await ids(gcp.datastream.Stream.isInstance, r => r.streamId);
  const profileIds = await ids(
    gcp.datastream.ConnectionProfile.isInstance,
    r => r.connectionProfileId
  );
  const commandCreates = await ids(command.local.Command.isInstance, r => r.create);
  return {
    dataset: datasetIds.includes(legacyDatasetId),
    stream: streamIds.includes('sv-1-scan-update-history'),
    destinationProfile: profileIds.includes('sv-1-scan-bq-cxn'),
    publicationAndSlot: commandCreates.some(create =>
      create?.includes('--publication-name="update_history_datastream_pub"')
    ),
  };
}

describe('configureScanBigQuery', () => {
  beforeAll(async () => {
    await pulumi.runtime.setMocks({
      newResource: args => ({ id: `${args.name}_id`, state: args.inputs }),
      call: args => {
        switch (args.token) {
          case 'gcp:organizations/getProject:getProject':
            return { projectId: process.env.CLOUDSDK_CORE_PROJECT };
          // the scan DB instance is looked up by name prefix and cluster label
          case 'gcp:sql/getDatabaseInstances:getDatabaseInstances':
            return {
              instances: [
                {
                  name: 'sv-1-cn-apps-pg-1234',
                  settings: [{ userLabels: { cluster: process.env.GCP_CLUSTER_BASENAME } }],
                },
              ],
            };
          default:
            return args.inputs;
        }
      },
    });
  });

  test('disabling the legacy Datastream removes its stream path but keeps its dataset', async () => {
    const resources = await installBigQuery(false, true, 'V068__app_activity_record_meta.sql');
    expect(await legacyPath(resources)).toEqual({
      dataset: true,
      stream: false,
      destinationProfile: false,
      publicationAndSlot: false,
    });
  });

  test('enabling the legacy Datastream installs its whole path', async () => {
    const resources = await installBigQuery(true, false, 'V068__app_activity_record_meta.sql');
    expect(await legacyPath(resources)).toEqual({
      dataset: true,
      stream: true,
      destinationProfile: true,
      publicationAndSlot: true,
    });
  });

  test.each([
    'V068__app_activity_record_meta.sql',
    'V074__string_interning.sql',
    'V077__verdict_add_round_number.sql',
    'V082__tbar_v1_1_asset_activity_columns.sql',
    'V085__partition_update_history.sql',
  ] as const)('both publication and slot commands wait for %s', async migration => {
    const resources = await installBigQuery(true, true, migration);
    const commands = resources.filter(command.local.Command.isInstance);
    const scripts = await Promise.all(
      commands.flatMap(c => [outputValue(c.create), outputValue(c.delete)])
    );
    expect(scripts).toHaveLength(4);
    for (const script of scripts) {
      expect(script).toContain(`--flyway-migration-to-wait-for="${migration}"`);
    }
  });

  test.each([
    ['V068__app_activity_record_meta.sql', false],
    ['V074__string_interning.sql', true],
  ] as const)('waiting for %s replicates interned_strings: %s', async (migration, replicated) => {
    const resources = await installBigQuery(true, true, migration);

    const commands = resources.filter(command.local.Command.isInstance);
    const scripts = await Promise.all(
      commands.flatMap(c => [outputValue(c.create), outputValue(c.delete)])
    );
    expect(scripts).toHaveLength(4);
    for (const script of scripts) {
      expect(/--tables-to-replicate-joined="[^"]*\binterned_strings\b/.test(script ?? '')).toBe(
        replicated
      );
    }

    const streams = resources.filter(gcp.datastream.Stream.isInstance);
    expect(streams).toHaveLength(2);
    for (const stream of streams) {
      const sourceConfig = await outputValue(stream.sourceConfig);
      const tables = (
        sourceConfig.postgresqlSourceConfig?.includeObjects?.postgresqlSchemas ?? []
      ).flatMap(schema => (schema.postgresqlTables ?? []).map(t => t.table));
      expect(tables).toContain('update_history_creates');
      expect(tables.includes('interned_strings')).toBe(replicated);
    }

    const routineIds = await Promise.all(
      resources.filter(gcp.bigquery.Routine.isInstance).map(r => outputValue(r.routineId))
    );
    expect(routineIds).toContain('sp_append_update_history_creates');
    expect(routineIds.includes('sp_append_interned_strings')).toBe(replicated);
    expect(routineIds.includes('sp_purge_old_records_interned_strings')).toBe(replicated);
  });

  test('interned_strings is appended by new key, the other tables by watermark', async () => {
    const resources = await installBigQuery(true, true, 'V074__string_interning.sql');
    const bodies = new Map<string, string>();
    for (const routine of resources.filter(gcp.bigquery.Routine.isInstance)) {
      const routineId = await outputValue(routine.routineId);
      if (routineId.startsWith('sp_append_')) {
        bodies.set(routineId, await outputValue(routine.definitionBody));
      }
    }
    expect(bodies.size).toBe(6);
    for (const [routineId, body] of bodies) {
      expect(body).not.toContain('{{');
      const byNewKey = routineId === 'sp_append_interned_strings';
      expect(body.includes('max_closed_timestamp')).toBe(!byNewKey);
      expect(body.includes('WHERE prod.id = staging.id')).toBe(byNewKey);
    }
  });

  test('appends copy the listed columns by name, checking production first if they change', async () => {
    const resources = await installBigQuery(
      true,
      true,
      'V082__tbar_v1_1_asset_activity_columns.sql'
    );
    const bodies = new Map<string, string>();
    for (const routine of resources.filter(gcp.bigquery.Routine.isInstance)) {
      bodies.set(await outputValue(routine.routineId), await outputValue(routine.definitionBody));
    }
    const appends = [...bodies].filter(([routineId]) => routineId.startsWith('sp_append_'));
    expect(appends).toHaveLength(6);
    const checking = new Set([
      'update_history_creates',
      'update_history_exercises',
      'scan_verdict_store',
      'app_activity_record_store',
    ]);
    for (const [routineId, body] of appends) {
      const tableName = routineId.replace('sp_append_', '');
      expect(body).not.toContain('{{');
      // an empty {{checkProdSchema}} leaves no blank lines behind
      expect(body).not.toContain('\n\n\n');
      expect(body).not.toContain('EXECUTE IMMEDIATE');
      expect(body).not.toContain('ALTER TABLE');
      expect(body).not.toContain('staging.*');
      expect(body).not.toContain('SELECT *');
      expect(body).toMatch(
        /INSERT INTO `[\w.-]+` \([^)]*`datastream_metadata`,\s+record_date\s+\)/
      );
      expect(body.includes('sp_check_prod_schema`(')).toBe(checking.has(tableName));
      if (checking.has(tableName)) {
        expect(body).toMatch(
          new RegExp(`sp_check_prod_schema\`\\('scan_sv_1_${tableName}', '${tableName}', \\[`)
        );
      }
    }
    expect(bodies.get('sp_append_scan_verdict_store')).toContain('`round_number`');
    expect(bodies.get('sp_append_app_activity_record_store')).toContain('`asset_activity_weights`');
  });

  test('a routine fixes and another checks the schemas of production tables', async () => {
    const resources = await installBigQuery(
      true,
      true,
      'V082__tbar_v1_1_asset_activity_columns.sql'
    );
    const routines = new Map<string, gcp.bigquery.Routine>();
    for (const routine of resources.filter(gcp.bigquery.Routine.isInstance)) {
      routines.set(await outputValue(routine.routineId), routine);
    }
    const check = routines.get('sp_check_prod_schema');
    const fix = routines.get('sp_fix_prod_schema');
    expect(check).toBeDefined();
    expect(fix).toBeDefined();
    for (const routine of [check!, fix!]) {
      expect(await outputValue(routine.routineType)).toBe('PROCEDURE');
      const args = await outputValue(routine.arguments);
      expect(args?.map(arg => arg.name)).toEqual([
        'staging_table_name',
        'prod_table_name',
        'columns',
      ]);
    }
    const checkBody = await outputValue(check!.definitionBody);
    // names the per-table fix routine
    expect(checkBody).toContain("'sp_fix_prod_schema', prod_table_name");
    expect(checkBody).toContain('RAISE USING MESSAGE');
    const fixBody = await outputValue(fix!.definitionBody);
    expect(fixBody.match(/EXECUTE IMMEDIATE/g)).toHaveLength(1);
    expect(fixBody).toContain("'ALTER TABLE `%s.%s.%s` %s'");
    expect(fixBody).toMatch(/STRING_AGG\(\s*FORMAT\('ADD COLUMN IF NOT EXISTS/);

    const queries = new Map<string, [string, string | undefined]>();
    for (const config of resources.filter(gcp.bigquery.DataTransferConfig.isInstance)) {
      const params = await outputValue(config.params);
      queries.set(await outputValue(config.displayName), [
        params.query,
        await outputValue(config.schedule),
      ]);
    }
    const [fixQuery, fixSchedule] = queries.get('mock Production Schema Hourly Fix')!;
    expect(
      fixQuery
        .split('\n')
        .map(call => /^ {2}CALL `[\w.-]+\.sp_fix_prod_schema_(\w+)`\(\);$/.exec(call)?.[1])
        .filter(tableName => tableName)
    ).toEqual([
      'update_history_creates',
      'update_history_exercises',
      'scan_verdict_store',
      'app_activity_record_store',
    ]);
    // each CALL in its own block, so that a failing one doesn't skip
    // the others
    expect(fixQuery.match(/EXCEPTION WHEN ERROR THEN/g)).toHaveLength(4);
    expect(fixQuery).toContain("RAISE USING MESSAGE = ARRAY_TO_STRING(failed, '; ');");
    const [, appendSchedule] = queries.get('mock_scan_verdict_store Hourly Append Pipeline')!;
    expect(fixSchedule).not.toBe(appendSchedule);
  });

  test.each([
    ['V077__verdict_add_round_number.sql', true],
    ['V074__string_interning.sql', false],
  ] as const)(
    'waiting for %s, the per-table fix fills `since` columns from staging: %s',
    async (migration, fills) => {
      const resources = await installBigQuery(true, true, migration);
      const bodies = new Map<string, string>();
      for (const routine of resources.filter(gcp.bigquery.Routine.isInstance)) {
        bodies.set(await outputValue(routine.routineId), await outputValue(routine.definitionBody));
      }
      const body = bodies.get('sp_fix_prod_schema_scan_verdict_store');
      expect(body).toBeDefined();
      expect(body).not.toContain('{{');
      expect(body).toMatch(
        /CALL `[\w.-]+\.sp_fix_prod_schema`\('scan_sv_1_scan_verdict_store', 'scan_verdict_store', \[.*\]\);/
      );
      expect(body!.includes('UPDATE')).toBe(fills);
      if (fills) {
        // only where production lacks a value that staging has
        expect(body).toContain(
          '`round_number` = COALESCE(prod.`round_number`, latest.`round_number`)'
        );
        expect(body).toContain('WHERE prod.record_date >= oldest_record_date');
        expect(body).toContain('AND prod.`row_id` = latest.`row_id`\n');
        expect(body).toContain(
          'AND ((prod.`round_number` IS NULL AND latest.`round_number` IS NOT NULL));'
        );
        // not only in the hour that adds the column
        expect(body).not.toContain('added');
        expect(body).toMatch(/column_name IN UNNEST\(\['round_number'\]\)/);
      }
    }
  );

  test('the fill matches every column of a composite primary key', async () => {
    const { fillSinceColumnsSql } = await import('./bigQuery');
    const sql = fillSinceColumnsSql(
      {
        primaryKey: 'verdict_row_id, view_id',
        datePartitionColumn: 'source_timestamp',
        timeType: 'datastream_metadata',
        appendMode: 'watermark',
        columns: ['verdict_row_id', 'view_id', 'x', 'datastream_metadata'],
        sinceColumns: ['x'],
        migratesSchema: true,
      },
      {
        project: 'proj',
        prodDataset: 'prod',
        stagingDataset: 'staging',
        prodTableName: 'p',
        stagingTableName: 's',
      }
    );
    expect(sql).toContain(
      'prod.`verdict_row_id` = latest.`verdict_row_id` AND prod.`view_id` = latest.`view_id`'
    );
    expect(sql).toContain('PARTITION BY verdict_row_id, view_id');
    expect(sql).toMatch(/staging\.`verdict_row_id`,\s+staging\.`view_id`,\s+staging\.`x`\n/);
    expect(sql).toContain('TIMESTAMP_MILLIS(staging.datastream_metadata.source_timestamp)');
  });

  test('neither Datastream backfills automatically', async () => {
    const resources = await installBigQuery(true, true, 'V074__string_interning.sql');
    const streams = resources.filter(gcp.datastream.Stream.isInstance);
    expect(streams).toHaveLength(2);
    for (const stream of streams) {
      expect(await outputValue(stream.backfillAll)).toBeUndefined();
      expect(await outputValue(stream.backfillNone)).toEqual({});
    }
  });
});

describe('flywayMigrationToWaitFor', () => {
  test('waits for V068 by default and allows V074, V077, V082 and V085', async () => {
    const { FlywayMigrationToWaitForSchema } = await import('./bigQueryFlywayMigration');
    expect(FlywayMigrationToWaitForSchema.parse(undefined)).toBe(
      'V068__app_activity_record_meta.sql'
    );
    for (const migration of [
      'V074__string_interning.sql',
      'V077__verdict_add_round_number.sql',
      'V082__tbar_v1_1_asset_activity_columns.sql',
      'V085__partition_update_history.sql',
    ] as const) {
      expect(FlywayMigrationToWaitForSchema.parse(migration)).toBe(migration);
    }
  });

  test.each([
    'V076__dso_unavailable_parties_store_id_pk.sql',
    'V074__string_interning',
    'V082__partition_update_history.sql',
  ])('rejects %s', async migration => {
    const { FlywayMigrationToWaitForSchema } = await import('./bigQueryFlywayMigration');
    expect(FlywayMigrationToWaitForSchema.safeParse(migration).success).toBe(false);
  });

  test('flywayMigrationVersion reads the version', async () => {
    const { flywayMigrationVersion } = await import('./bigQueryFlywayMigration');
    expect(flywayMigrationVersion('V074__string_interning.sql')).toBe(74);
    expect(() => flywayMigrationVersion('R__repeatable.sql')).toThrow(/R__repeatable\.sql/);
  });

  test('checkFlywayMigrationsExist names the missing migrations', async () => {
    const { checkFlywayMigrationsExist } = await import('./bigQueryFlywayMigration');
    const dir = fs.mkdtempSync(`${os.tmpdir()}/flyway-`);
    try {
      fs.writeFileSync(`${dir}/V068__app_activity_record_meta.sql`, '');
      expect(() =>
        checkFlywayMigrationsExist(dir, [
          'V068__app_activity_record_meta.sql',
          'V074__string_interning.sql',
        ])
      ).toThrow(/: V074__string_interning\.sql$/);
    } finally {
      fs.rmSync(dir, { recursive: true });
    }
  });
});

describe('replicatedTablesFor', () => {
  test('replicates tables and copies columns from their `since` on', async () => {
    const { replicatedTablesFor } = await import('./bigQueryReplicatedTables');
    const v068 = replicatedTablesFor('V068__app_activity_record_meta.sql');
    const v074 = replicatedTablesFor('V074__string_interning.sql');
    const v077 = replicatedTablesFor('V077__verdict_add_round_number.sql');
    const v082 = replicatedTablesFor('V082__tbar_v1_1_asset_activity_columns.sql');
    expect(Object.keys(v068)).not.toContain('interned_strings');
    expect(v074.interned_strings?.columns).toEqual(['id', 'value', 'datastream_metadata']);
    for (const tables of [v074, v077, v082]) {
      expect(Object.keys(tables)).toEqual([...Object.keys(v068), 'interned_strings']);
    }
    const verdicts = (tables: typeof v068) => tables.scan_verdict_store?.columns ?? [];
    expect(verdicts(v074)).toContain('record_time');
    expect(verdicts(v074)).not.toContain('round_number');
    // added by V077, and in scan's database on 0.10.0 already
    expect(verdicts(v077)).toContain('round_number');
    expect(verdicts(v077).slice(-1)).toEqual(['datastream_metadata']);
    const activity = (tables: typeof v068) => tables.app_activity_record_store?.columns ?? [];
    expect(activity(v077)).not.toContain('asset_activity_weights');
    expect(activity(v082)).toContain('asset_activity_weights');
  });

  test('only tables whose columns have a `since` migrate their schema', async () => {
    const { replicatedTablesFor } = await import('./bigQueryReplicatedTables');
    const migrating = Object.entries(replicatedTablesFor('V074__string_interning.sql'))
      .filter(([, table]) => table.migratesSchema)
      .map(([tableName]) => tableName);
    expect(migrating).toEqual([
      'update_history_creates',
      'update_history_exercises',
      'scan_verdict_store',
      'app_activity_record_store',
    ]);
  });

  // Production tables keep the columns they were created with, and
  // only a column with a `since` makes its table check and fix them.
  // So a column added to Postgres later must get a `since`; a column
  // listed without one would fail every append of an older production
  // table. Extend this only for a new table, or a new column on a table
  // with a `since` of its own that no deployment replicates yet.
  test('columns without a `since` are those production tables were created with', async () => {
    const { loadReplicatedTablesConfig } = await import('./bigQueryReplicatedTables');
    const plainColumns = Object.fromEntries(
      Object.entries(loadReplicatedTablesConfig()).map(([tableName, table]) => [
        tableName,
        table.columns.filter(column => typeof column === 'string'),
      ])
    );
    const createdWith = {
      update_history_creates: [
        'history_id',
        'row_id',
        'ingested_at',
        'event_id',
        'update_row_id',
        'contract_id',
        'created_at',
        'template_id_package_id',
        'template_id_module_name',
        'template_id_entity_name',
        'package_name',
        'create_arguments',
        'signatories',
        'observers',
        'contract_key',
        'record_time',
        'update_id',
        'domain_id',
        'migration_id',
      ],
      update_history_exercises: [
        'history_id',
        'row_id',
        'ingested_at',
        'event_id',
        'update_row_id',
        'child_event_ids',
        'choice',
        'template_id_package_id',
        'template_id_module_name',
        'template_id_entity_name',
        'contract_id',
        'consuming',
        'argument',
        'result',
        'package_name',
        'interface_id_package_id',
        'interface_id_module_name',
        'interface_id_entity_name',
        'acting_parties',
        'record_time',
        'update_id',
        'domain_id',
        'migration_id',
      ],
      scan_verdict_store: [
        'row_id',
        'ingested_at',
        'migration_id',
        'domain_id',
        'record_time',
        'finalization_time',
        'submitting_participant_uid',
        'verdict_result',
        'mediator_group',
        'update_id',
        'submitting_parties',
        'transaction_root_views',
        'history_id',
        'total_traffic_cost',
        'envelope_traffic_costs',
      ],
      scan_verdict_transaction_view_store: [
        'verdict_row_id',
        'view_id',
        'informees',
        'confirming_parties',
        'sub_views',
      ],
      app_activity_record_store: [
        'history_id',
        'verdict_row_id',
        'round_number',
        'app_provider_parties',
        'app_activity_weights',
      ],
      interned_strings: ['id', 'value'],
    };
    try {
      expect(plainColumns).toEqual(createdWith);
    } catch (e) {
      throw new Error(
        'A column without a `since` changed. Do NOT edit this test for that: give a new ' +
          'column the `since` of the migration that adds it, in ' +
          'bigquery-replicated-tables.yaml. Only the cases in the comment above this test ' +
          `extend its list.\n\n${(e as Error).message}`
      );
    }
  });

  test('rejects a `since` that is not an allowed migration', async () => {
    const { ReplicatedTablesConfigSchema } = await import('./bigQueryReplicatedTables');
    const table = {
      primaryKey: 'id',
      datePartitionColumn: 'source_timestamp',
      timeType: 'datastream_metadata',
      appendMode: 'newKeys',
    };
    expect(
      ReplicatedTablesConfigSchema.safeParse({
        t: { ...table, columns: ['id', { name: 'x', since: 'V074__string_interning.sql' }] },
      }).success
    ).toBe(true);
    expect(
      ReplicatedTablesConfigSchema.safeParse({
        t: {
          ...table,
          columns: ['id', { name: 'x', since: 'V076__dso_unavailable_parties_store_id_pk.sql' }],
        },
      }).success
    ).toBe(false);
  });
});

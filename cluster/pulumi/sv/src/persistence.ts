// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as postgres from '@canton-network/splice-pulumi-common/src/postgres';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace, PersistenceConfig } from '@canton-network/splice-pulumi-common';
import { SvConfig } from '@canton-network/splice-pulumi-common-sv/src/config';
import { spliceConfig } from '@canton-network/splice-pulumi-common/src/config/config';

export async function installAppsPostgres(
  xns: ExactNamespace,
  config: SvConfig
): Promise<postgres.Postgres> {
  const defaultPostgres = config.splitPostgresInstances
    ? undefined
    : await postgres.installPostgres(
        xns,
        'postgres',
        'postgres',
        config.version,
        spliceConfig.pulumiProjectConfig.cloudSql,
        spliceConfig.pulumiProjectConfig.defaultSplicePostgresConfig,
        false,
        {
          logicalDecoding: !!config.scanApp?.bigQuery,
        }
      );

  const appsPostgres =
    defaultPostgres ||
    (await postgres.installPostgres(
      xns,
      `cn-apps-pg`,
      `cn-apps-pg`,
      config.version,
      config.appsPg?.cloudSql ?? spliceConfig.pulumiProjectConfig.cloudSql,
      spliceConfig.pulumiProjectConfig.defaultSplicePostgresConfig,
      true,
      {
        logicalDecoding: !!config.scanApp?.bigQuery,
      }
    ));

  return appsPostgres;
}

export function persistenceConfig(
  postgresDb: postgres.Postgres,
  dbName: string
): PersistenceConfig {
  const dbNameO = pulumi.Output.create(dbName);
  return {
    host: postgresDb.address,
    databaseName: dbNameO,
    secretName: postgresDb.secretName,
    schema: dbNameO,
    user: pulumi.Output.create('cnadmin'),
    port: pulumi.Output.create(5432),
    postgresName: postgresDb.instanceName,
  };
}

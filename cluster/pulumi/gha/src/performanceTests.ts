// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import {
  CloudSqlConfigSchema,
  CLUSTER_BASENAME,
  config,
  ExactNamespace,
  GCP_REGION,
  GCP_ZONE,
  installPostgresPasswordSecret,
} from '@canton-network/splice-pulumi-common';

export interface PerformanceTestDb {
  db: gcp.sql.Database;
  address: pulumi.Output<string>;
  secretName: pulumi.Output<string>;
}

// trimmed down version of common/postgres.ts that will not blow up due to imports of global variables
export function createCloudSQLInstanceForPerformanceTests(
  ghaNamespace: ExactNamespace
): PerformanceTestDb {
  // Keep this in sync with the prod cloudSql config, so that perf tests run against an instance that matches Mainnet.
  const mainnetLikeConfig = CloudSqlConfigSchema.parse({
    enabled: true,
    protected: false,
    // db-perf-optimized-N-4: 4 vCPU, 32 GB RAM, matching prod as of 30.10.2026
    tier: 'db-perf-optimized-N-4',
    enterprisePlus: true,
    flags: {
      random_page_cost: '1.1',
      temp_file_limit: '2147483647',
      max_wal_size: '20480', // 20GB, but GCP requires a plain number in MB
      maintenance_work_mem: '2000000', // 2Gi, but GCP requires a plain number in KB
      work_mem: '32768', // 32MB, matching the prod (high-memory) instance
    },
  });
  const zone = GCP_ZONE || config.requireEnv('DB_CLOUDSDK_COMPUTE_ZONE');
  const instance = new gcp.sql.DatabaseInstance('performance-tests-db', {
    databaseVersion: 'POSTGRES_18',
    deletionProtection: false,
    region: GCP_REGION,
    settings: {
      deletionProtectionEnabled: false,
      activationPolicy: 'ALWAYS', // no other option seems to work
      databaseFlags: Object.keys(mainnetLikeConfig.flags).map(name => {
        return { name, value: mainnetLikeConfig.flags[name] };
      }),
      backupConfiguration: {
        enabled: false,
        pointInTimeRecoveryEnabled: false,
      },
      insightsConfig: {
        // presumably useful in case of sudden slow queries
        queryInsightsEnabled: true,
        enhancedQueryInsightsEnabled: mainnetLikeConfig.enterprisePlus,
      },
      tier: mainnetLikeConfig.tier,
      edition: mainnetLikeConfig.enterprisePlus ? 'ENTERPRISE_PLUS' : 'ENTERPRISE',
      ...(mainnetLikeConfig.enterprisePlus
        ? {
            dataCacheConfig: {
              dataCacheEnabled: true,
            },
          }
        : undefined),
      ipConfiguration: {
        ipv4Enabled: false,
        privateNetwork: pulumi.interpolate`projects/${gcp.organizations.getProjectOutput({}).name}/global/networks/default`,
        enablePrivatePathForGoogleCloudServices: true,
      },
      userLabels: {
        cluster: CLUSTER_BASENAME,
      },
      locationPreference: {
        zone,
      },
      maintenanceWindow: { day: 7, hour: 12 }, // sunday at 12 UTC while there are no tests running
    },
  });

  const logicalDb = new gcp.sql.Database(
    `performance-tests-db-cantonnet`,
    {
      instance: instance.name,
      name: 'cantonnet',
    },
    {
      parent: instance,
      deletedWith: instance,
      protect: false,
    }
  );

  const password = generatePassword('performance-tests-db-pw', {
    parent: logicalDb,
    protect: false,
  }).result;
  const passwordSecret = installPostgresPasswordSecret(
    ghaNamespace,
    password,
    'performance-tests-db-secret'
  );

  new gcp.sql.User(
    `performance-tests-db-user`,
    {
      instance: instance.name,
      name: 'cnadmin',
      password: password,
    },
    {
      parent: logicalDb,
      deletedWith: logicalDb,
      dependsOn: [logicalDb],
      protect: false,
    }
  );

  return {
    db: logicalDb,
    address: instance.privateIpAddress,
    secretName: passwordSecret.metadata.name,
  };
}

function generatePassword(
  name: string,
  opts?: pulumi.ResourceOptions & Required<Pick<pulumi.ResourceOptions, 'parent'>>
): random.RandomPassword {
  return new random.RandomPassword(
    name,
    {
      length: 16,
      overrideSpecial: '_%@',
      special: true,
    },
    opts
  );
}

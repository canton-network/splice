// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import * as command from '@pulumi/command';
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as path from 'path';
import * as pulumi from '@pulumi/pulumi';
import * as ip from 'ip';
import * as fs from 'fs';
import {
  InstalledHelmChart,
  installPostgresPasswordSecret,
} from '@canton-network/splice-pulumi-common';
import {
  clusterProdLike,
  config,
} from '@canton-network/splice-pulumi-common/src/config';
import { spliceConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import {
  Postgres,
  CloudPostgres,
  generatePassword,
  privateNetworkId,
} from '@canton-network/splice-pulumi-common/src/postgres';
import {
  ExactNamespace,
  CLUSTER_BASENAME,
  commandScriptPath,
} from '@canton-network/splice-pulumi-common/src/utils';

// ============================================================================
// ENVIRONMENT & PIPELINE CONFIGURATION
// Reads dynamic flags directly using the shared `config` instance
// ============================================================================

const enableLegacyDatastream = config.envFlag('ENABLE_LEGACY_DATASTREAM', true);
const enableStagProdDatastream = config.envFlag('ENABLE_STAG_PROD_DATASTREAM', true);

const THREE_DAYS_MS = 3 * 24 * 60 * 60 * 1000;
const rawExpiration = config.optionalEnv('PROD_TABLE_EXPIRATION_MS');
const prodTableExpirationMs = rawExpiration ? Number(rawExpiration) : THREE_DAYS_MS;

const legacyDesiredState: 'RUNNING' | 'PAUSED' = enableLegacyDatastream ? 'RUNNING' : 'PAUSED';
const stagProdDesiredState: 'RUNNING' | 'PAUSED' = enableStagProdDatastream ? 'RUNNING' : 'PAUSED';

// ============================================================================
// PIPELINE & DATABASE CONSTANTS
// ============================================================================

interface ScanBigQueryConfig {
  dataset: string;
  prefix: string;
}

interface PostgresPassword {
  contents: pulumi.Output<string>;
  secret: k8s.core.v1.Secret;
}

const dbPort = 5432;
const replicatorUserName = 'bqdatastream';

// Stream 1 (Legacy) CDC Replication Configuration - MATCHES ACTIVE PRODUCTION EXACTLY
const replicationSlotName = 'update_history_datastream_r_slot';
const publicationName = 'update_history_datastream_pub';

// Stream 2 (Stag-Prod) CDC Replication Configuration - NEW PIPELINE
const replicationSlotNameStagProd = 'update_history_datastream_stag_prod_r_slot';
const publicationNameStagProd = 'update_history_datastream_stag_prod_pub';

const flywayMigrationToWaitFor = 'V068__app_activity_record_meta.sql';

// ============================================================================
// SINGLE SOURCE OF TRUTH: REPLICATED TABLE CONFIGURATION
// ============================================================================

interface ReplicatedTableConfig {
  primaryKey: string;
  datePartitionColumn: string;
  timeType: 'micros' | 'datastream_metadata' | 'partition_time';
}

const replicatedTables: Record<string, ReplicatedTableConfig> = {
  'update_history_creates': {
    primaryKey: 'row_id',
    datePartitionColumn: 'record_time',
    timeType: 'micros',
  },
  'update_history_exercises': {
    primaryKey: 'row_id',
    datePartitionColumn: 'record_time',
    timeType: 'micros',
  },
  'scan_verdict_store': {
    primaryKey: 'row_id',
    datePartitionColumn: 'record_time',
    timeType: 'micros',
  },
  'scan_verdict_transaction_view_store': {
    primaryKey: 'verdict_row_id, view_id',
    datePartitionColumn: 'source_timestamp',
    timeType: 'datastream_metadata',
  },
  'app_activity_record_store': {
    primaryKey: 'row_id',
    datePartitionColumn: 'record_time',
    timeType: 'micros',
  },
};

const tablesToReplicate = Object.keys(replicatedTables);

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

function cloudsdkComputeRegion() {
  return config.requireEnv('CLOUDSDK_COMPUTE_REGION');
}

function scanAppDatabaseName(postgres: Postgres) {
  return `scan_${postgres.namespace.logicalName.replace(/-/g, '_')}`;
}

function pickDatastreamPeeringCidr(): string {
  const baseCidr = config.requireEnv('GCP_MASTER_IPV4_CIDR');
  const baseSubnet = ip.cidrSubnet(baseCidr);

  if (baseSubnet.subnetMaskLength !== 28) {
    throw new Error(`Expected a /28 CIDR, but got ${baseCidr}`);
  }

  return ip.fromLong(ip.toLong(baseSubnet.networkAddress) + baseSubnet.length) + '/29';
}

function installNatVm(postgres: CloudPostgres): gcp.compute.Instance {
  const vmName = `${postgres.namespace.logicalName}-nat-vm`;
  const startupScript = pulumi.interpolate`#! /bin/bash

export DB_ADDR=${postgres.address}
export DB_PORT=${dbPort}

echo 1 > /proc/sys/net/ipv4/ip_forward

md_url_prefix="http://169.254.169.254/computeMetadata/v1/instance"
vm_nic_ip="$(curl -H "Metadata-Flavor: Google" $md_url_prefix/network-interfaces/0/ip)"

iptables -t nat -F

iptables -t nat -A PREROUTING \\
     -p tcp --dport $DB_PORT \\
     -j DNAT \\
     --to-destination $DB_ADDR

iptables -t nat -A POSTROUTING \\
     -p tcp --dport $DB_PORT \\
     -j SNAT \\
     --to-source $vm_nic_ip

iptables-save
`;

  return new gcp.compute.Instance(vmName, {
    machineType: 'e2-micro',
    zone: postgres.zone,
    bootDisk: {
      initializeParams: {
        image: 'debian-cloud/debian-12',
      },
    },
    networkInterfaces: [
      {
        network: 'default',
        accessConfigs: [{}],
      },
    ],
    metadata: {
      'enable-osconfig': 'TRUE',
      'enable-oslogin': 'true',
      'startup-script': startupScript,
    },
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });
}

function installDatastreamIamRoles(): pulumi.Resource[] {
  const currentProject = gcp.organizations.getProjectOutput({});
  const projectId = currentProject.apply(p => p.projectId!);

  const datastreamBqDataEditor = new gcp.projects.IAMMember('datastream-bq-data-editor', {
    project: projectId,
    role: 'roles/bigquery.dataEditor',
    member: currentProject.apply(p => `serviceAccount:service-${p.number}@gcp-sa-datastream.iam.gserviceaccount.com`),
  });

  const datastreamBqJobUser = new gcp.projects.IAMMember('datastream-bq-job-user', {
    project: projectId,
    role: 'roles/bigquery.jobUser',
    member: currentProject.apply(p => `serviceAccount:service-${p.number}@gcp-sa-datastream.iam.gserviceaccount.com`),
  });

  return [datastreamBqDataEditor, datastreamBqJobUser];
}

// ============================================================================
// DATASTREAM PIPELINE DEFINITIONS
// ============================================================================

function installDatastream(
  postgres: CloudPostgres,
  source: gcp.datastream.ConnectionProfile,
  destination: gcp.datastream.ConnectionProfile,
  bigQueryDataset: gcp.bigquery.Dataset,
  pubRepSlots: pulumi.Resource,
  iamPermissions: pulumi.Resource[] = []
): gcp.datastream.Stream {
  const streamName = `${postgres.namespace.logicalName}-scan-update-history`;
  const schemaName = scanAppDatabaseName(postgres);
  return new gcp.datastream.Stream(
    streamName,
    {
      location: cloudsdkComputeRegion(),
      streamId: streamName,
      displayName: streamName,
      desiredState: legacyDesiredState,
      sourceConfig: {
        postgresqlSourceConfig: {
          includeObjects: {
            postgresqlSchemas: [
              {
                schema: schemaName,
                postgresqlTables: tablesToReplicate.map(table => ({ table })),
              },
            ],
          },
          publication: publicationName,
          replicationSlot: replicationSlotName,
        },
        sourceConnectionProfile: source.name,
      },
      destinationConfig: {
        bigqueryDestinationConfig: {
          singleTargetDataset: {
            datasetId: pulumi.interpolate`projects/${bigQueryDataset.project}/datasets/${bigQueryDataset.datasetId}`,
          },
          dataFreshness: clusterProdLike ? '14400s' : '0s',
        },
        destinationConnectionProfile: destination.name,
      },
      backfillAll: {},
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [postgres, source, destination, bigQueryDataset, pubRepSlots] }
  );
}

function installDatastream_stag_prod(
  postgres: CloudPostgres,
  source: gcp.datastream.ConnectionProfile,
  destination: gcp.datastream.ConnectionProfile,
  bigQueryDataset: gcp.bigquery.Dataset,
  pubRepSlots: pulumi.Resource,
  iamPermissions: pulumi.Resource[] = []
): gcp.datastream.Stream {
  const streamName = `${postgres.namespace.logicalName}-scan-stag-production-datastream`;
  const schemaName = scanAppDatabaseName(postgres);
  return new gcp.datastream.Stream(
    streamName,
    {
      location: cloudsdkComputeRegion(),
      streamId: streamName,
      displayName: streamName,
      desiredState: stagProdDesiredState,
      sourceConfig: {
        postgresqlSourceConfig: {
          includeObjects: {
            postgresqlSchemas: [
              {
                schema: schemaName,
                postgresqlTables: tablesToReplicate.map(table => ({ table })),
              },
            ],
          },
          publication: publicationNameStagProd,
          replicationSlot: replicationSlotNameStagProd,
        },
        sourceConnectionProfile: source.name,
      },
      destinationConfig: {
        bigqueryDestinationConfig: {
          singleTargetDataset: {
            datasetId: pulumi.interpolate`projects/${bigQueryDataset.project}/datasets/${bigQueryDataset.datasetId}`,
          },
          dataFreshness: '0s',
          appendOnly: {},
        },
        destinationConnectionProfile: destination.name,
      },
      backfillAll: {},
      ruleSets: tablesToReplicate.map(tableName => ({
        objectFilter: {
          sourceObjectIdentifier: {
            postgresqlIdentifier: {
              schema: schemaName,
              table: tableName,
            },
          },
        },
        customizationRules: [{
          bigqueryPartitioning: {
            ingestionTimePartition: {
              partitioningTimeGranularity: 'PARTITIONING_TIME_GRANULARITY_HOUR'
            }
          },
        }],
      })),
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { 
      dependsOn: [postgres, source, destination, bigQueryDataset, pubRepSlots],
      replaceOnChanges: ["destinationConfig"],
      deleteBeforeReplace: true
    }
  );
}

// ============================================================================
// BIGQUERY DATASET CREATION
// ============================================================================

function installBigqueryDataset(scanBigQuery: ScanBigQueryConfig): gcp.bigquery.Dataset {
  return new gcp.bigquery.Dataset(scanBigQuery.dataset, {
    datasetId: scanBigQuery.dataset,
    friendlyName: `${scanBigQuery.dataset} Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: true,
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });
}

function installBigqueryStagingDataset(scanBigQuery: ScanBigQueryConfig): gcp.bigquery.Dataset {
  return new gcp.bigquery.Dataset(`${scanBigQuery.dataset}-staging`, {
    datasetId: `${scanBigQuery.dataset}_staging`,
    friendlyName: `${scanBigQuery.dataset} Staging Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: true,
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });
}

function installBigqueryProdDataset(scanBigQuery: ScanBigQueryConfig): gcp.bigquery.Dataset {
  return new gcp.bigquery.Dataset(`${scanBigQuery.dataset}-prod`, {
    datasetId: `${scanBigQuery.dataset}_prod`,
    friendlyName: `${scanBigQuery.dataset} Production Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: false,
    defaultTableExpirationMs: prodTableExpirationMs,
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });
}

// ============================================================================
// HOURLY DEDUPLICATION & SCHEDULED QUERIES
// ============================================================================

const rawSqlTemplate = fs.readFileSync(path.join(__dirname, 'hourly_append.sql'), 'utf8');

function installHourlyScheduledQueries(
  postgres: CloudPostgres,
  stagingDataset: gcp.bigquery.Dataset,
  prodDataset: gcp.bigquery.Dataset
) {
  const currentProject = gcp.organizations.getProjectOutput({});
  const projectId = currentProject.apply(p => p.projectId!);
  const schemaName = scanAppDatabaseName(postgres);

  const transferServiceAgentPermission = new gcp.projects.IAMMember('bq-transfer-token-creator', {
    project: projectId,
    role: 'roles/iam.serviceAccountTokenCreator',
    member: currentProject.apply(p => `serviceAccount:service-${p.number}@gcp-sa-bigquerydatatransfer.iam.gserviceaccount.com`),
  });

  Object.entries(replicatedTables).forEach(([tableName, tableConfig]) => {
    const primaryKeyExpr = tableConfig.primaryKey;
    const colName = tableConfig.datePartitionColumn;
    
    let recordTimestampExpr: string;
    if (tableConfig.timeType === 'micros') {
      recordTimestampExpr = `TIMESTAMP_MICROS(staging.${colName})`;
    } else if (tableConfig.timeType === 'datastream_metadata') {
      recordTimestampExpr = `TIMESTAMP_MILLIS(staging.datastream_metadata.source_timestamp)`;
    } else {
      recordTimestampExpr = `staging._PARTITIONTIME`;
    }

    const recordDateExpr = `CAST(${recordTimestampExpr} AS DATE)`;

    const procedureBody = pulumi.all([
      projectId, 
      prodDataset.datasetId, 
      stagingDataset.datasetId
    ]).apply(([proj, prodDs, stagingDs]) => {
      const prodTable = `\`${proj}.${prodDs}.${tableName}\``;
      const stagingTable = `\`${proj}.${stagingDs}.${schemaName}_${tableName}\``;
      const watermarksTable = `\`${proj}.${prodDs}.pipeline_watermarks\``;
      const prodInfoSchema = `\`${proj}.${prodDs}.INFORMATION_SCHEMA.TABLES\``;

      return rawSqlTemplate
        .replaceAll('{{tableName}}', tableName)
        .replaceAll('{{primaryKeyExpr}}', primaryKeyExpr)
        .replaceAll('{{recordTimestampExpr}}', recordTimestampExpr)
        .replaceAll('{{recordDateExpr}}', recordDateExpr)
        .replaceAll('{{prodTable}}', prodTable)
        .replaceAll('{{stagingTable}}', stagingTable)
        .replaceAll('{{watermarksTable}}', watermarksTable)
        .replaceAll('{{prodInfoSchema}}', prodInfoSchema);
    });

    const routineId = `sp_append_${tableName}`;

    const appendRoutine = new gcp.bigquery.Routine(`${tableName}-append-routine`, {
      datasetId: prodDataset.datasetId,
      routineId: routineId,
      routineType: 'PROCEDURE',
      language: 'SQL',
      definitionBody: procedureBody,
    });

    new gcp.bigquery.DataTransferConfig(`${tableName}-hourly-append-v12`, {
      displayName: `${tableName} Hourly Append Loop Dynamic Watermark v12`,
      location: cloudsdkComputeRegion(),
      serviceAccountName: pulumi.interpolate`bigquery@${projectId}.iam.gserviceaccount.com`,
      dataSourceId: 'scheduled_query',
      schedule: 'every 1 hours from 00:07 to 23:07',
      
      params: {
        query: pulumi.interpolate`CALL \`${projectId}.${prodDataset.datasetId}.${routineId}\`();`,
      },
    }, { 
      dependsOn: [transferServiceAgentPermission, appendRoutine], 
      deleteBeforeReplace: true 
    });
  });
}

// ============================================================================
// CONNECTION PROFILES & NETWORKING
// ============================================================================

function installBigqueryConnectionProfile(
  postgres: CloudPostgres,
  bigQuery: gcp.bigquery.Dataset,
  pcc: gcp.datastream.PrivateConnection
): gcp.datastream.ConnectionProfile {
  const profileName = `${postgres.namespace.logicalName}-scan-bq-cxn`;
  return new gcp.datastream.ConnectionProfile(
    profileName,
    {
      connectionProfileId: profileName,
      displayName: profileName,
      location: cloudsdkComputeRegion(),
      bigqueryProfile: {},
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [bigQuery, pcc] }
  );
}

function installBigqueryStagingConnectionProfile(
  postgres: CloudPostgres,
  bigQuery: gcp.bigquery.Dataset,
  pcc: gcp.datastream.PrivateConnection
): gcp.datastream.ConnectionProfile {
  const profileName = `${postgres.namespace.logicalName}-scan-bq-staging-cxn`;
  return new gcp.datastream.ConnectionProfile(
    profileName,
    {
      connectionProfileId: profileName,
      displayName: profileName,
      location: cloudsdkComputeRegion(),
      bigqueryProfile: {},
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [bigQuery, pcc] }
  );
}

function installPostgresConnectionProfile(
  postgres: CloudPostgres,
  scan: InstalledHelmChart,
  natVm: gcp.compute.Instance,
  connection: gcp.datastream.PrivateConnection,
  replicatorPassword: PostgresPassword
): gcp.datastream.ConnectionProfile {
  const profileName = `${postgres.namespace.logicalName}-scan-update-history-cxn`;

  return new gcp.datastream.ConnectionProfile(
    profileName,
    {
      connectionProfileId: profileName,
      displayName: profileName,
      location: cloudsdkComputeRegion(),
      postgresqlProfile: {
        hostname: natVm.networkInterfaces[0].networkIp,
        port: dbPort,
        username: replicatorUserName,
        password: replicatorPassword.contents,
        database: scanAppDatabaseName(postgres),
      },
      privateConnectivity: {
        privateConnection: connection.name,
      },
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [natVm, connection, postgres.databaseInstance, scan] }
  );
}

function installPrivateConnectivityConfiguration(
  postgres: CloudPostgres
): gcp.datastream.PrivateConnection {
  const privateConnectionName = `${postgres.namespace.logicalName}-scan-update-history-datastream-vpc`;
  return new gcp.datastream.PrivateConnection(
    privateConnectionName,
    {
      privateConnectionId: privateConnectionName,
      displayName: privateConnectionName,
      location: cloudsdkComputeRegion(),
      vpcPeeringConfig: { subnet: pickDatastreamPeeringCidr(), vpc: privateNetworkId },
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { deleteBeforeReplace: true }
  );
}

function installDatastreamToNatVmFirewallRule(
  namespace: ExactNamespace,
  source: gcp.datastream.PrivateConnection,
  natVm: gcp.compute.Instance
): gcp.compute.Firewall {
  const firewallRuleName = `${namespace.logicalName}-datastream-to-nat`;

  return new gcp.compute.Firewall(firewallRuleName, {
    name: firewallRuleName,
    direction: 'INGRESS',
    priority: 42,
    network: 'default',
    allows: [
      {
        protocol: 'tcp',
        ports: [dbPort.toString()],
      },
    ],
    sourceRanges: source.vpcPeeringConfig.apply(peeringConfig =>
      peeringConfig ? [peeringConfig.subnet] : []
    ),
    destinationRanges: [natVm.networkInterfaces[0].networkIp],
  });
}

// ============================================================================
// POSTGRESQL AUTHENTICATION & REPLICATION SLOT PROVISIONING
// ============================================================================

function installReplicatorPassword(postgres: CloudPostgres): PostgresPassword {
  const secretName = `${postgres.namespace.logicalName}-${replicatorUserName}-passwd`;
  const password = generatePassword(`${postgres.instanceName}-${replicatorUserName}-passwd`, {
    parent: postgres,
    protect: spliceConfig.pulumiProjectConfig.cloudSql.protected,
  }).result;
  return {
    contents: password,
    secret: installPostgresPasswordSecret(postgres.namespace, password, secretName),
  };
}

function createPostgresReplicatorUser(
  postgres: CloudPostgres,
  password: PostgresPassword
): gcp.sql.User {
  const name = `${postgres.namespace.logicalName}-user-${replicatorUserName}`;
  return new gcp.sql.User(
    name,
    {
      instance: postgres.databaseInstance.name,
      name: replicatorUserName,
      password: password.contents,
    },
    {
      parent: postgres,
      deletedWith: postgres.databaseInstance,
      retainOnDelete: true,
      protect: spliceConfig.pulumiProjectConfig.cloudSql.protected,
      dependsOn: [postgres.databaseInstance, password.secret],
    }
  );
}

function createPublicationAndReplicationSlots(
  postgres: CloudPostgres,
  replicatorUser: gcp.sql.User,
  scan: InstalledHelmChart
): { slot1?: pulumi.Resource; slot2?: pulumi.Resource } {
  const dbName = scanAppDatabaseName(postgres);
  const schemaName = dbName;
  const scriptPath = commandScriptPath('cluster/pulumi/canton-network/bigquery-cloudsql.sh');

  let slot1: command.local.Command | undefined;
  if (enableLegacyDatastream) {
    const scriptArgsSlot1 = pulumi.interpolate`\\
      --private-network-project="${gcp.organizations.getProjectOutput({}).apply(proj => proj.name)}" \\
      --compute-region="${cloudsdkComputeRegion()}" \\
      --service-account-email="${postgres.databaseInstance.serviceAccountEmailAddress}" \\
      --schema-name="${schemaName}" \\
      --tables-to-replicate-joined="${tablesToReplicate.join(', ')}" \\
      --postgres-user-name="${postgres.user.name}" \\
      --publication-name="${publicationName}" \\
      --replication-slot-name="${replicationSlotName}" \\
      --replicator-user-name="${replicatorUserName}" \\
      --postgres-instance-name="${postgres.databaseInstance.name}" \\
      --scan-app-database-name="${dbName}" \\
      --flyway-migration-to-wait-for="${flywayMigrationToWaitFor}" \\
      `;

    slot1 = new command.local.Command(
      `${postgres.namespace.logicalName}-${replicatorUserName}-pub-replicate-slots`,
      {
        create: pulumi.interpolate`'${scriptPath}' create-pub-rep-slot ${scriptArgsSlot1}`,
        delete: pulumi.interpolate`'${scriptPath}' delete-pub-rep-slot ${scriptArgsSlot1}`,
      },
      {
        deletedWith: postgres.databaseInstance,
        dependsOn: [scan, postgres.databaseInstance, replicatorUser],
        deleteBeforeReplace: true,
      } 
    );
  }

  let slot2: command.local.Command | undefined;
  if (enableStagProdDatastream) {
    const projectId = gcp.organizations.getProjectOutput({}).apply(proj => proj.projectId);
    const baseArgs = [
      pulumi.interpolate`--private-network-project="${projectId}"`,
      pulumi.interpolate`--compute-region="${cloudsdkComputeRegion()}"`,
      pulumi.interpolate`--service-account-email="${postgres.databaseInstance.serviceAccountEmailAddress}"`,
      pulumi.interpolate`--schema-name="${schemaName}"`,
      pulumi.interpolate`--tables-to-replicate-joined="${tablesToReplicate.join(', ')}"`,
      pulumi.interpolate`--postgres-user-name="${postgres.user.name}"`,
      pulumi.interpolate`--replicator-user-name="${replicatorUserName}"`,
      pulumi.interpolate`--postgres-instance-name="${postgres.databaseInstance.name}"`,
      pulumi.interpolate`--scan-app-database-name="${dbName}"`,
      pulumi.interpolate`--flyway-migration-to-wait-for="${flywayMigrationToWaitFor}"`,
    ];
    const baseArgsString = pulumi.all(baseArgs).apply(args => args.join(' '));
    const scriptArgsSlot2 = pulumi.interpolate`${baseArgsString} --publication-name="${publicationNameStagProd}" --replication-slot-name="${replicationSlotNameStagProd}"`;
    
    const slot2Dependencies: pulumi.Resource[] = [scan, postgres.databaseInstance, replicatorUser];
    if (slot1) {
      slot2Dependencies.push(slot1);
    }

    slot2 = new command.local.Command(
      `${postgres.namespace.logicalName}-${replicatorUserName}-pub-replicate-slot-2`,
      {
        create: pulumi.interpolate`'${scriptPath}' create-pub-rep-slot ${scriptArgsSlot2}`,
        delete: pulumi.interpolate`'${scriptPath}' delete-pub-rep-slot ${scriptArgsSlot2}`,
      },
      {
        deletedWith: postgres.databaseInstance,
        dependsOn: slot2Dependencies,
      }
    );
  }

  return { slot1, slot2 };
}

// ============================================================================
// MAIN ENTRYPOINT
// ============================================================================

export function configureScanBigQuery(
  postgres: CloudPostgres,
  scanBigQuery: ScanBigQueryConfig,
  scan: InstalledHelmChart
): void {
  if (!enableLegacyDatastream && !enableStagProdDatastream) {
    return;
  }

  const datastreamIamRoles = installDatastreamIamRoles();

  const passwordSecret = installReplicatorPassword(postgres);
  const slots = createPublicationAndReplicationSlots(
    postgres,
    createPostgresReplicatorUser(postgres, passwordSecret),
    scan
  );

  const natVm = installNatVm(postgres);
  const pcc = installPrivateConnectivityConfiguration(postgres);
  installDatastreamToNatVmFirewallRule(postgres.namespace, pcc, natVm);

  const sourceProfile = installPostgresConnectionProfile(
    postgres,
    scan,
    natVm,
    pcc,
    passwordSecret
  );

  if (enableLegacyDatastream && slots.slot1) {
    const legacyDataset = installBigqueryDataset(scanBigQuery);
    const legacyDestinationProfile = installBigqueryConnectionProfile(
      postgres,
      legacyDataset,
      pcc
    );

    installDatastream(
      postgres, 
      sourceProfile, 
      legacyDestinationProfile, 
      legacyDataset, 
      slots.slot1,
      datastreamIamRoles
    );
  }

  if (enableStagProdDatastream && slots.slot2) {
    const stagingDataset = installBigqueryStagingDataset(scanBigQuery);
    const prodDataset = installBigqueryProdDataset(scanBigQuery);
    const stagingDestinationProfile = installBigqueryStagingConnectionProfile(
      postgres,
      stagingDataset,
      pcc
    );

    installDatastream_stag_prod(
      postgres, 
      sourceProfile, 
      stagingDestinationProfile, 
      stagingDataset, 
      slots.slot2,
      datastreamIamRoles
    );

    installHourlyScheduledQueries(postgres, stagingDataset, prodDataset);
  }

  return;
}
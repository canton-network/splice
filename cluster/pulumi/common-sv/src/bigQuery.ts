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
import { ScanBigQueryConfig } from './singleSvConfig';

// ============================================================================
// PIPELINE CONFIGURATION & TYPES
// ============================================================================

const THREE_DAYS_MS = 3 * 24 * 60 * 60 * 1000;

// export interface ScanBigQueryConfig {
//   dataset: string;
//   prefix: string;
//   enableLegacyDatastream?: boolean;
//   enableStagProdDatastream?: boolean;
//   legacyDesiredState?: 'RUNNING' | 'PAUSED';
//   stagProdDesiredState?: 'RUNNING' | 'PAUSED';
//   prodTableExpirationMs?: number;
// }

interface PostgresPassword {
  contents: pulumi.Output<string>;
  secret: k8s.core.v1.Secret;
}

const dbPort = 5432;
const replicatorUserName = 'bqdatastream';

// Remove legacy datastream configuration once migration to stag-prod pipeline is verified.
// issue: https://github.com/canton-network/splice/issues/6656
// TODO (#6656) Remove legacy datastream configuration once migration to stag-prod pipeline is verified

const replicationSlotName = 'update_history_datastream_r_slot';
const publicationName = 'update_history_datastream_pub';


// Stream 2 (Stag-Prod) CDC Replication Configuration
const replicationSlotNameStagProd = 'update_history_datastream_stag_prod_r_slot';
const publicationNameStagProd = 'update_history_datastream_stag_prod_pub';

const flywayMigrationToWaitFor = 'V068__app_activity_record_meta.sql';

// ============================================================================
// SINGLE SOURCE OF TRUTH: REPLICATED TABLE CONFIGURATION
// ============================================================================
// what tables from Scan to replicate to BigQuery
interface ReplicatedTableConfig {
  primaryKey: string;
  datePartitionColumn: string;
  timeType: 'micros' | 'datastream_metadata';
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
  // assert GCP_MASTER_IPV4_CIDR is a /28 CIDR
  if (baseSubnet.subnetMaskLength !== 28) {
    throw new Error(`Expected a /28 CIDR, but got ${baseCidr}`);
  }

  return ip.fromLong(ip.toLong(baseSubnet.networkAddress) + baseSubnet.length) + '/29';
}

function installNatVm(postgres: CloudPostgres): gcp.compute.Instance {
  const vmName = `${postgres.namespace.logicalName}-nat-vm`;
  // from https://cloud.google.com/datastream/docs/private-connectivity#set-up-reverse-proxy
  const startupScript = pulumi.interpolate`#! /bin/bash

export DB_ADDR=${postgres.address}
export DB_PORT=${dbPort}

# Enable the VM to receive packets whose destinations do
# not match any running process local to the VM
echo 1 > /proc/sys/net/ipv4/ip_forward

# Ask the Metadata server for the IP address of the VM nic0
# network interface:
md_url_prefix="http://169.254.169.254/computeMetadata/v1/instance"
vm_nic_ip="$(curl -H "Metadata-Flavor: Google" $md_url_prefix/network-interfaces/0/ip)"

# Clear any existing iptables NAT table entries (all chains):
iptables -t nat -F

# Create a NAT table entry in the prerouting chain, matching
# any packets with destination database port, changing the destination
# IP address of the packet to the SQL instance IP address:
iptables -t nat -A PREROUTING \\
     -p tcp --dport $DB_PORT \\
     -j DNAT \\
     --to-destination $DB_ADDR

# Create a NAT table entry in the postrouting chain, matching
# any packets with destination database port, changing the source IP
# address of the packet to the NAT VM's primary internal IPv4 address:
iptables -t nat -A POSTROUTING \\
     -p tcp --dport $DB_PORT \\
     -j SNAT \\
     --to-source $vm_nic_ip

# Save iptables configuration:
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
        accessConfigs: [{}], // ephemeral external IP
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
  desiredState: 'RUNNING' | 'PAUSED'
): gcp.datastream.Stream {
  const streamName = `${postgres.namespace.logicalName}-scan-update-history`;
  const schemaName = scanAppDatabaseName(postgres);
  return new gcp.datastream.Stream(
    streamName,
    {
      location: cloudsdkComputeRegion(),
      streamId: streamName,
      displayName: streamName,
      desiredState: desiredState,
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
          // editing dataFreshness does not alter existing BQ tables, see its
          // docstring or https://github.com/canton-network/splice/issues/2011
          dataFreshness: clusterProdLike ? '14400s' : '0s',
        },
        destinationConnectionProfile: destination.name,
      },
      backfillAll: {},
      labels: {
        cluster: CLUSTER_BASENAME,
        datastream_id: 'legacy',
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
  desiredState: 'RUNNING' | 'PAUSED'
): gcp.datastream.Stream {
  const streamName = `${CLUSTER_BASENAME}-${postgres.namespace.logicalName}-stag-production-datastream`;
  const schemaName = scanAppDatabaseName(postgres);
  return new gcp.datastream.Stream(
    streamName,
    {
      location: cloudsdkComputeRegion(),
      streamId: streamName,
      displayName: streamName,
      desiredState: desiredState,
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
        datastream_id: 'stag_prod',
      },
    },
    { 
      dependsOn: [postgres, source, destination, bigQueryDataset, pubRepSlots]
      
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
    deleteContentsOnDestroy: true, //retaining old value
    // TODO (DACH-NY/canton-network-internal#343) reduce time travel window from 7-day default to 2 days if
    // it makes a cost difference
    labels: {
      cluster: CLUSTER_BASENAME,
      datastream_id: 'legacy',
    },
  });
}
/* TODO (DACH-NY/canton-network-internal#341) remove this comment when enabled on all relevant clusters
If you see an error like this
  gcp:datastream:ConnectionProfile (sv-4-scan-bq-cxn):
    error: 1 error occurred:
      * Error creating ConnectionProfile: googleapi: Error 403: Datastream API has not been used in project da-cn-scratchnet before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/datastream.googleapis.com/overview?project=da-cn-scratchnet then retry. If you enabled this API recently, wait a few minutes for the action to propagate to our systems and retry.

or the same for

  gcp:datastream:PrivateConnection (sv-4-scan-update-history-datastream-vpc)

you have to manually enable the API as described for that cluster.
- done for da-cn-scratchnet
- done for da-cn-ci-2
 */

function installBigqueryStagingDataset(scanBigQuery: ScanBigQueryConfig): gcp.bigquery.Dataset {
  return new gcp.bigquery.Dataset(`${scanBigQuery.dataset}-staging`, {
    datasetId: `${scanBigQuery.dataset}_staging`,
    friendlyName: `${scanBigQuery.dataset} Staging Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: true,
    defaultTableExpirationMs: THREE_DAYS_MS,
    labels: {
      cluster: CLUSTER_BASENAME,
      datastream_id: 'stag_prod',
    },
  });
}

function installBigqueryProdDataset(
  scanBigQuery: ScanBigQueryConfig,
): gcp.bigquery.Dataset {
  return new gcp.bigquery.Dataset(`${scanBigQuery.dataset}-prod`, {
    datasetId: `${scanBigQuery.dataset}_prod`,
    friendlyName: `${scanBigQuery.dataset} Production Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: true,
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
      const unreachable: never = tableConfig.timeType;
      throw new Error(`impossible time config: ${unreachable}`);
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
        .replaceAll('{{schemaName}}', schemaName)
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

    new gcp.bigquery.DataTransferConfig(`${CLUSTER_BASENAME}_${tableName}-hourly-append`, {
      displayName: `${CLUSTER_BASENAME}_${tableName} Hourly Append Pipeline`,
      location: cloudsdkComputeRegion(),
      serviceAccountName: pulumi.interpolate`bigquery@${projectId}.iam.gserviceaccount.com`,
      dataSourceId: 'scheduled_query',
      schedule: 'every 1 hours from 00:07 to 23:07',
      
      params: {
        query: pulumi.interpolate`CALL \`${projectId}.${prodDataset.datasetId}.${routineId}\`();`,
      },
    }, { 
      dependsOn: [transferServiceAgentPermission, appendRoutine],
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
      bigqueryProfile: {},// just a sumtype marker
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
 // TODO (#454) may have to await scan migration or pub/rep slots command
  return new gcp.datastream.ConnectionProfile(
    profileName,
    {
      connectionProfileId: profileName,
      displayName: profileName,
      location: cloudsdkComputeRegion(),
      postgresqlProfile: {
        hostname: natVm.networkInterfaces[0].networkIp,// NAT's private IP
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
// TODO (DACH-NY/canton-network-internal#342) if we disable default egress rule, we need another firewall
// rule for Nat VM -> Postgres
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

/*
For the SQL below to apply, the user/operator applying the pulumi
needs the 'Cloud SQL Editor' IAM role in the relevant GCP project
 */

function createPublicationAndReplicationSlots(
  postgres: CloudPostgres,
  replicatorUser: gcp.sql.User,
  scan: InstalledHelmChart,
  enableLegacy: boolean,
  enableStagProd: boolean
): {
  slot1?: command.local.Command;
  slot2?: command.local.Command;
} {
  // ---------------------------------------------------------------------------
  // 1. Shared Environment & Project Setup
  // ---------------------------------------------------------------------------

  const dbName = scanAppDatabaseName(postgres);
  const schemaName = dbName;
  const scriptPath = commandScriptPath('cluster/pulumi/canton-network/bigquery-cloudsql.sh');

  const projectId = gcp.organizations
    .getProjectOutput({})
    .apply(proj => proj.projectId);

  const commonDependencies = [
    scan,
    postgres.databaseInstance,
    replicatorUser,
  ];

  // ---------------------------------------------------------------------------
  // 2. Base Arguments Split (Matches Stored Deployment Ordering & Formatting)
  // ---------------------------------------------------------------------------

  // Prefix arguments (Arguments 1–6)
  const baseArgsPrefix: pulumi.Input<string>[] = [
    pulumi.interpolate`--private-network-project="${projectId}"`,
    pulumi.interpolate`--compute-region="${cloudsdkComputeRegion()}"`,
    pulumi.interpolate`--service-account-email="${postgres.databaseInstance.serviceAccountEmailAddress}"`,
    pulumi.interpolate`--schema-name="${schemaName}"`,
    pulumi.interpolate`--tables-to-replicate-joined="${tablesToReplicate.join(', ')}"`,
    pulumi.interpolate`--postgres-user-name="${postgres.user.name}"`,
  ];

  // Suffix arguments (Arguments 9–12)
  const baseArgsSuffix: pulumi.Input<string>[] = [
    pulumi.interpolate`--replicator-user-name="${replicatorUserName}"`,
    pulumi.interpolate`--postgres-instance-name="${postgres.databaseInstance.name}"`,
    pulumi.interpolate`--scan-app-database-name="${dbName}"`,
    pulumi.interpolate`--flyway-migration-to-wait-for="${flywayMigrationToWaitFor}"`,
  ];

  // Constructs full CLI command matching original deployment state:
  // - Places publication & slot args in positions 7 & 8
  // - Uses 6-space indentation (` \\\n      `)
  // - Includes trailing backslash continuation (` \\\n      `)
  const buildScriptCommand = (
    action: string,
    slotArgs: pulumi.Input<string>[]
  ): pulumi.Output<string> => {
    const allArgs = [...baseArgsPrefix, ...slotArgs, ...baseArgsSuffix];

    return pulumi.all(allArgs).apply(args => {
      const formattedArgs = args.join(' \\\n      ');
      return `'${scriptPath}' ${action} \\\n      ${formattedArgs} \\\n      `;
    });
  };

  // ---------------------------------------------------------------------------
  // 3. Legacy Datastream Slot (Slot 1)
  // ---------------------------------------------------------------------------

  let slot1: command.local.Command | undefined;

  if (enableLegacy) {
    const slot1Args: pulumi.Input<string>[] = [
      pulumi.interpolate`--publication-name="${publicationName}"`,
      pulumi.interpolate`--replication-slot-name="${replicationSlotName}"`,
    ];

    slot1 = new command.local.Command(
      `${postgres.namespace.logicalName}-${replicatorUserName}-pub-replicate-slots`,
      {
        create: buildScriptCommand('create-pub-rep-slot', slot1Args),
        delete: buildScriptCommand('delete-pub-rep-slot', slot1Args),
      },
      {
        deletedWith: postgres.databaseInstance,
        dependsOn: commonDependencies,
        deleteBeforeReplace: true,
      }
    );
  }

  // ---------------------------------------------------------------------------
  // 4. Stag-Prod Datastream Slot (Slot 2)
  // ---------------------------------------------------------------------------

  let slot2: command.local.Command | undefined;

  if (enableStagProd) {
    const slot2Args: pulumi.Input<string>[] = [
      pulumi.interpolate`--publication-name="${publicationNameStagProd}"`,
      pulumi.interpolate`--replication-slot-name="${replicationSlotNameStagProd}"`,
    ];

    slot2 = new command.local.Command(
      `${postgres.namespace.logicalName}-${replicatorUserName}-pub-replicate-slot-2`,
      {
        create: buildScriptCommand('create-pub-rep-slot', slot2Args),
        delete: buildScriptCommand('delete-pub-rep-slot', slot2Args),
      },
      {
        deletedWith: postgres.databaseInstance,
        dependsOn: commonDependencies,
        deleteBeforeReplace: true,
      }
    );
  }

  // ---------------------------------------------------------------------------
  // 5. Return Created Slots
  // ---------------------------------------------------------------------------

  return {
    slot1,
    slot2,
  };
}
// ============================================================================
// MAIN ENTRYPOINT
// ============================================================================
export function configureScanBigQuery(
  postgres: CloudPostgres,
  scanBigQuery: ScanBigQueryConfig,
  scan: InstalledHelmChart
): void {
  // Destructure all config properties at function entry with fallback defaults
  // we can change this to enableLegacyDatastream to false or PAUSED once we verify the stag-prod pipeline is working correctly and we want to disable the legacy pipeline
  const {
    enableLegacyDatastream ,
    enableStagProdDatastream ,
    legacyDesiredState,
    stagProdDesiredState,
  } = scanBigQuery;

  if (!enableLegacyDatastream && !enableStagProdDatastream) {
    throw new Error(
      'configureScanBigQuery was called, but both legacy and stag-prod Datastreams are disabled.'
    );
  }



  const passwordSecret = installReplicatorPassword(postgres);
  const slots = createPublicationAndReplicationSlots(
    postgres,
    createPostgresReplicatorUser(postgres, passwordSecret),
    scan,
    enableLegacyDatastream,
    enableStagProdDatastream
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
      legacyDesiredState
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
      stagProdDesiredState
    );

    installHourlyScheduledQueries(postgres, stagingDataset, prodDataset);
  }
}
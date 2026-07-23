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
import { clusterProdLike, config } from '@canton-network/splice-pulumi-common/src/config';
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

// Stream 1 Postgres CDC Configuration
const replicationSlotName = 'update_history_datastream_r_slot';
const publicationName = 'update_history_datastream_pub';

// Stream 2 Postgres CDC Configuration
const replicationSlotNameStagProd = 'update_history_datastream_stag_prod_r_slot';
const publicationNameStagProd = 'update_history_datastream_stag_prod_pub';

// What tables from Scan to replicate to BigQuery
const tablesToReplicate = [
  'update_history_creates',
  'update_history_exercises',
  'scan_verdict_store',
  'scan_verdict_transaction_view_store',
  'app_activity_record_store',
];

const flywayMigrationToWaitFor = 'V068__app_activity_record_meta.sql';

interface TableTimeConfig {
  column: string;
  type: 'micros' | 'millis' | 'timestamp' | 'datastream_metadata' | 'partition_time';
}

const tableTimeMappings: Record<string, TableTimeConfig> = {
  'update_history_creates': { column: 'record_time', type: 'micros' },
  'update_history_exercises': { column: 'record_time', type: 'micros' },
  'scan_verdict_store': { column: 'record_time', type: 'micros' }, 
  'scan_verdict_transaction_view_store': { column: 'source_timestamp', type: 'datastream_metadata' },  
  'app_activity_record_store': { column: 'record_time', type: 'micros' },
};

function cloudsdkComputeRegion() {
  return config.requireEnv('CLOUDSDK_COMPUTE_REGION');
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

// Old Datastream Definition (Stream 1) - needs to deleted after testing
function installDatastream(
  postgres: CloudPostgres,
  source: gcp.datastream.ConnectionProfile,
  destination: gcp.datastream.ConnectionProfile,
  bigQueryDataset: gcp.bigquery.Dataset,
  pubRepSlots: pulumi.Resource
): gcp.datastream.Stream {
  const streamName = `${postgres.namespace.logicalName}-scan-update-history`;
  const schemaName = scanAppDatabaseName(postgres);
  return new gcp.datastream.Stream(
    streamName,
    {
      location: cloudsdkComputeRegion(),
      streamId: streamName,
      displayName: streamName,
      desiredState: 'RUNNING',
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

// New Datastream with Staging and Prod setup
function installDatastream_stag_prod(
  postgres: CloudPostgres,
  source: gcp.datastream.ConnectionProfile,
  destination: gcp.datastream.ConnectionProfile,
  bigQueryDataset: gcp.bigquery.Dataset,
  pubRepSlots: pulumi.Resource
): gcp.datastream.Stream {
  const streamName = `${postgres.namespace.logicalName}-scan-stag-prod`;
  const schemaName = scanAppDatabaseName(postgres);
  return new gcp.datastream.Stream(
    streamName,
    {
      location: cloudsdkComputeRegion(),
      streamId: streamName,
      displayName: streamName,
      desiredState: 'RUNNING',
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

// Target Dataset for Old Datastream (Stream 1) - to be deleted after testing
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

// Target Dataset for Stream 2
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

const THREE_DAYS_MS = 3 * 24 * 60 * 60 * 1000;

function installBigqueryProdDataset(scanBigQuery: ScanBigQueryConfig): gcp.bigquery.Dataset {
  return new gcp.bigquery.Dataset(`${scanBigQuery.dataset}-prod`, {
    datasetId: `${scanBigQuery.dataset}_prod`,
    friendlyName: `${scanBigQuery.dataset} Production Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: false,
    defaultTableExpirationMs: THREE_DAYS_MS,
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });
}

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

  tablesToReplicate.forEach(tableName => {
    const timeConfig = tableTimeMappings[tableName] || { column: 'record_time', type: 'micros' };
    const colName = timeConfig.column;
    
    let recordTimestampExpr: string;
    if (timeConfig.type === 'micros') {
      recordTimestampExpr = `TIMESTAMP_MICROS(staging.${colName})`;
    } else if (timeConfig.type === 'millis') {
      recordTimestampExpr = `TIMESTAMP_MILLIS(staging.${colName})`;
    } else if (timeConfig.type === 'timestamp') {
      recordTimestampExpr = `CAST(staging.${colName} AS TIMESTAMP)`;
    } else if (timeConfig.type === 'datastream_metadata') {
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

    new gcp.bigquery.DataTransferConfig(`${tableName}-hourly-append-v11`, {
      displayName: `${tableName} Hourly Append Loop Dynamic Watermark v11`,
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

function installBigqueryConnectionProfile(
  postgres: CloudPostgres,
  suffix: string,
  bigQuery: gcp.bigquery.Dataset,
  pcc: gcp.datastream.PrivateConnection
): gcp.datastream.ConnectionProfile {
  const profileName = `${postgres.namespace.logicalName}-scan-bq-${suffix}-cxn`;
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

function scanAppDatabaseName(postgres: Postgres) {
  return `scan_${postgres.namespace.logicalName.replace(/-/g, '_')}`;
}

// Single Shared Source Profile for both streams
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

// Creates both slots and publications sequentially on PostgreSQL instance
function createPublicationAndReplicationSlots(
  postgres: CloudPostgres,
  replicatorUser: gcp.sql.User,
  scan: InstalledHelmChart
) {
  const dbName = scanAppDatabaseName(postgres);
  const schemaName = dbName;
  const scriptPath = commandScriptPath('cluster/pulumi/canton-network/bigquery-cloudsql.sh');
  
  const baseScriptArgs = pulumi.interpolate`\\
      --private-network-project="${gcp.organizations.getProjectOutput({}).apply(proj => proj.name)}" \\
      --compute-region="${cloudsdkComputeRegion()}" \\
      --service-account-email="${postgres.databaseInstance.serviceAccountEmailAddress}" \\
      --schema-name="${schemaName}" \\
      --tables-to-replicate-joined="${tablesToReplicate.join(', ')}" \\
      --postgres-user-name="${postgres.user.name}" \\
        // --publication-name="${publicationName}" \\ -- commented out because publication name is now passed in slot-specific args
        // --replication-slot-name="${replicationSlotName}" \\ -- commented out because replication slot name is now passed in slot-specific args
      --replicator-user-name="${replicatorUserName}" \\
      --postgres-instance-name="${postgres.databaseInstance.name}" \\
      --scan-app-database-name="${scanAppDatabaseName(postgres)}" \\
      --flyway-migration-to-wait-for="${flywayMigrationToWaitFor}" \\
      `;

      
  // script for testing starts here - can be deleted after testing
          const scriptArgsSlot1 = pulumi.interpolate`${baseScriptArgs} --publication-name="${publicationName}" --replication-slot-name="${replicationSlotName}"`;
          const scriptArgsSlot2 = pulumi.interpolate`${baseScriptArgs} --publication-name="${publicationNameStagProd}" --replication-slot-name="${replicationSlotNameStagProd}"`;

          // Create Slot/Pub 1
          const slot1 = new command.local.Command(
              `${postgres.namespace.logicalName}-${replicatorUserName}-pub-replicate-slot-1`,
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

            // Create Slot/Pub 2 (depends on slot1)
            const slot2 = new command.local.Command(
              `${postgres.namespace.logicalName}-${replicatorUserName}-pub-replicate-slot-2`,
              {
                create: pulumi.interpolate`'${scriptPath}' create-pub-rep-slot ${scriptArgsSlot2}`,
                delete: pulumi.interpolate`'${scriptPath}' delete-pub-rep-slot ${scriptArgsSlot2}`,
              },
              {
                deletedWith: postgres.databaseInstance,
                dependsOn: [scan, postgres.databaseInstance, replicatorUser, slot1],
                deleteBeforeReplace: true,
              }
            );

  return slot2;

  // script for testing ends here - can be deleted after testing
    
// uncomment the lines below to have just one datastream - Staging and Prod will not be created
    //   return new command.local.Command(
    //   `${postgres.namespace.logicalName}-${replicatorUserName}-pub-replicate-slots`,
    //   {
    //     create: pulumi.interpolate`'${path}' create-pub-rep-slot ${scriptArgs}`,
    //     delete: pulumi.interpolate`'${path}' delete-pub-rep-slot ${scriptArgs}`,
    //   },
    //   {
    //     deletedWith: postgres.databaseInstance,
    //     dependsOn: [scan, postgres.databaseInstance, replicatorUser],
    //     deleteBeforeReplace: true,
    //   }
    // );
}

export function configureScanBigQuery(
  postgres: CloudPostgres,
  scanBigQuery: ScanBigQueryConfig,
  scan: InstalledHelmChart
): void {
  const passwordSecret = installReplicatorPassword(postgres);
  const pubRepSlots = createPublicationAndReplicationSlots(
    postgres,
    createPostgresReplicatorUser(postgres, passwordSecret),
    scan
  );

  const natVm = installNatVm(postgres);
  
  // Create 3 BigQuery Datasets: Stream 1 Target, Stream 2 Staging Target, & Final Prod
  const legacyDataset = installBigqueryDataset(scanBigQuery);
  const stagingDataset = installBigqueryStagingDataset(scanBigQuery);
  const prodDataset = installBigqueryProdDataset(scanBigQuery);

  const pcc = installPrivateConnectivityConfiguration(postgres);
  
  // 1. Shared Source Profile (Postgres NAT VM connection)
  const sourceProfile = installPostgresConnectionProfile(
    postgres,
    scan,
    natVm,
    pcc,
    passwordSecret
  );

  // 2. Separate Destination Profiles for each dataset
  const legacyDestinationProfile = installBigqueryConnectionProfile(
    postgres,
    'legacy',
    legacyDataset,
    pcc
  );
  
  const stagingDestinationProfile = installBigqueryConnectionProfile(
    postgres,
    'staging',
    stagingDataset,
    pcc
  );

  installDatastreamToNatVmFirewallRule(postgres.namespace, pcc, natVm);

  // Datastream 1: Reads via Slot 1 (`update_history_datastream_r_slot`) -> legacyDataset
  installDatastream(
    postgres, 
    sourceProfile, 
    legacyDestinationProfile, 
    legacyDataset, 
    pubRepSlots
  );
  
  // Datastream 2: Reads via Slot 2 (`update_history_datastream_stag_prod_r_slot`) -> stagingDataset
  installDatastream_stag_prod(
    postgres, 
    sourceProfile, 
    stagingDestinationProfile, 
    stagingDataset, 
    pubRepSlots
  );

  // Scheduled Batch Appends: stagingDataset -> prodDataset
  installHourlyScheduledQueries(postgres, stagingDataset, prodDataset); 

  return;
}

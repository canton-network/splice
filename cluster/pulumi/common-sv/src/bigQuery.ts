// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as command from '@pulumi/command';
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as ip from 'ip';
import {
  InstalledHelmChart,
  installPostgresPasswordSecret,
} from '@canton-network/splice-pulumi-common';
import { clusterProdLike, config } from '@canton-network/splice-pulumi-common/src/config';
import { spliceConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import {
  defaultUserName,
  generatePassword,
  getCloudSdkZone,
  privateNetworkId,
} from '@canton-network/splice-pulumi-common/src/postgres';
import {
  ExactNamespace,
  CLUSTER_BASENAME,
  GCP_PROJECT,
  GCP_REGION,
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
const replicationSlotName = 'update_history_datastream_r_slot';
const publicationName = 'update_history_datastream_pub';
// what tables from Scan to replicate to BigQuery
const tablesToReplicate = [
  'update_history_creates',
  'update_history_exercises',
  'scan_verdict_store',
  'scan_verdict_transaction_view_store',
  'app_activity_record_store',
];
const flywayMigrationToWaitFor = 'V068__app_activity_record_meta.sql';

function cloudsdkComputeRegion() {
  return config.requireEnv('CLOUDSDK_COMPUTE_REGION');
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

function installNatVm(
  namespace: ExactNamespace,
  zone: string,
  databaseInstance: gcp.sql.DatabaseInstance
): gcp.compute.Instance {
  const vmName = `${namespace.logicalName}-nat-vm`;
  // from https://cloud.google.com/datastream/docs/private-connectivity#set-up-reverse-proxy
  const startupScript = pulumi.interpolate`#! /bin/bash

export DB_ADDR=${databaseInstance.privateIpAddress}
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
    zone,
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

function installDatastream(
  namespace: ExactNamespace,
  databaseInstance: gcp.sql.DatabaseInstance,
  source: gcp.datastream.ConnectionProfile,
  destination: gcp.datastream.ConnectionProfile,
  bigQueryDataset: gcp.bigquery.Dataset,
  pubRepSlots: pulumi.Resource
): gcp.datastream.Stream {
  const streamName = `${namespace.logicalName}-scan-update-history`;
  const schemaName = scanAppDatabaseName(namespace);
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
          // editing dataFreshness does not alter existing BQ tables, see its
          // docstring or https://github.com/canton-network/splice/issues/2011
          dataFreshness: clusterProdLike ? '14400s' : '0s',
        },
        destinationConnectionProfile: destination.name,
      },
      backfillAll: {},
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [databaseInstance, source, destination, bigQueryDataset, pubRepSlots] }
  );
}

function installBigqueryDataset(scanBigQuery: ScanBigQueryConfig): gcp.bigquery.Dataset {
  return new gcp.bigquery.Dataset(scanBigQuery.dataset, {
    datasetId: scanBigQuery.dataset,
    friendlyName: `${scanBigQuery.dataset} Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: true,
    // TODO (DACH-NY/canton-network-internal#343) reduce time travel window from 7-day default to 2 days if
    // it makes a cost difference
    labels: {
      cluster: CLUSTER_BASENAME,
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

function installBigqueryConnectionProfile(
  namespace: ExactNamespace,
  bigQuery: gcp.bigquery.Dataset,
  pcc: gcp.datastream.PrivateConnection
): gcp.datastream.ConnectionProfile {
  const profileName = `${namespace.logicalName}-scan-bq-cxn`;
  return new gcp.datastream.ConnectionProfile(
    profileName,
    {
      connectionProfileId: profileName,
      displayName: profileName,
      location: cloudsdkComputeRegion(),
      bigqueryProfile: {}, // just a sumtype marker
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [bigQuery, pcc] }
  );
}

function scanAppDatabaseName(namespace: ExactNamespace): string {
  return `scan_${namespace.logicalName.replace(/-/g, '_')}`;
}

function installPostgresConnectionProfile(
  namespace: ExactNamespace,
  databaseInstance: gcp.sql.DatabaseInstance,
  scan: InstalledHelmChart | undefined,
  natVm: gcp.compute.Instance,
  connection: gcp.datastream.PrivateConnection,
  replicatorPassword: PostgresPassword
): gcp.datastream.ConnectionProfile {
  const profileName = `${namespace.logicalName}-scan-update-history-cxn`;

  // TODO (#454) may have to await scan migration or pub/rep slots command
  return new gcp.datastream.ConnectionProfile(
    profileName,
    {
      connectionProfileId: profileName,
      displayName: profileName,
      location: cloudsdkComputeRegion(),
      postgresqlProfile: {
        hostname: natVm.networkInterfaces[0].networkIp, // NAT's private IP
        port: dbPort,
        username: replicatorUserName,
        password: replicatorPassword.contents,
        database: scanAppDatabaseName(namespace),
      },
      privateConnectivity: {
        privateConnection: connection.name,
      },
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [natVm, connection, databaseInstance, ...(scan !== undefined ? [scan] : [])] }
  );
}

function installPrivateConnectivityConfiguration(
  namespace: ExactNamespace
): gcp.datastream.PrivateConnection {
  const privateConnectionName = `${namespace.logicalName}-scan-update-history-datastream-vpc`;
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

// TODO (DACH-NY/canton-network-internal#342) if we disable default egress rule, we need another firewall
// rule for Nat VM -> Postgres

function installReplicatorPassword(namespace: ExactNamespace): PostgresPassword {
  const secretName = `${namespace.logicalName}-${replicatorUserName}-passwd`;
  const password = generatePassword(`cn-apps-pg-${replicatorUserName}-passwd`, {
    aliases: [
      {
        parent: getLegacyParentUrn(namespace),
      },
    ],
    protect: spliceConfig.pulumiProjectConfig.cloudSql.protected,
  }).result;
  return {
    contents: password,
    secret: installPostgresPasswordSecret(namespace, password, secretName),
  };
}

function createPostgresReplicatorUser(
  namespace: ExactNamespace,
  databaseInstance: gcp.sql.DatabaseInstance,
  password: PostgresPassword
): gcp.sql.User {
  const name = `${namespace.logicalName}-user-${replicatorUserName}`;
  return new gcp.sql.User(
    name,
    {
      instance: databaseInstance.name,
      name: replicatorUserName,
      password: password.contents,
    },
    {
      aliases: [
        {
          parent: getLegacyParentUrn(namespace),
        },
      ],
      protect: spliceConfig.pulumiProjectConfig.cloudSql.protected,
      dependsOn: [password.secret],
    }
  );
}

/*
For the SQL below to apply, the user/operator applying the pulumi
needs the 'Cloud SQL Editor' IAM role in the relevant GCP project
 */

function createPublicationAndReplicationSlots(
  namespace: ExactNamespace,
  databaseInstance: gcp.sql.DatabaseInstance,
  replicatorUser: gcp.sql.User,
  scan: InstalledHelmChart | undefined
) {
  const dbName = scanAppDatabaseName(namespace);
  const schemaName = dbName;
  const path = commandScriptPath('cluster/pulumi/canton-network/bigquery-cloudsql.sh');
  const scriptArgs = pulumi.interpolate`\\
      --private-network-project="${gcp.organizations.getProjectOutput({}).apply(proj => proj.name)}" \\
      --compute-region="${cloudsdkComputeRegion()}" \\
      --service-account-email="${databaseInstance.serviceAccountEmailAddress}" \\
      --schema-name="${schemaName}" \\
      --tables-to-replicate-joined="${tablesToReplicate.join(', ')}" \\
      --postgres-user-name="${defaultUserName}" \\
      --publication-name="${publicationName}" \\
      --replication-slot-name="${replicationSlotName}" \\
      --replicator-user-name="${replicatorUserName}" \\
      --postgres-instance-name="${databaseInstance.name}" \\
      --scan-app-database-name="${scanAppDatabaseName(namespace)}" \\
      --flyway-migration-to-wait-for="${flywayMigrationToWaitFor}" \\
      `;
  return new command.local.Command(
    `${namespace.logicalName}-${replicatorUserName}-pub-replicate-slots`,
    {
      create: pulumi.interpolate`'${path}' create-pub-rep-slot ${scriptArgs}`,
      delete: pulumi.interpolate`'${path}' delete-pub-rep-slot ${scriptArgs}`,
    },
    {
      dependsOn: [databaseInstance, replicatorUser, ...(scan !== undefined ? [scan] : [])],
      deleteBeforeReplace: true,
    }
  );
}

export async function configureScanBigQuery({
  namespace,
  bigQueryConfig,
  scanReference,
}: ScanBigQueryArgs): Promise<ScanBigQuery> {
  const zone = getCloudSdkZone();
  const [databaseInstance, scanChart] = await (async () => {
    switch (scanReference.type) {
      case 'local':
        return [scanReference.databaseInstance, scanReference.chart];
      case 'external':
        return [await getScanDb(scanReference.databaseInstanceNamePrefix, zone), undefined];
    }
  })();
  const passwordSecret = installReplicatorPassword(namespace);
  const pubRepSlots = createPublicationAndReplicationSlots(
    namespace,
    databaseInstance,
    createPostgresReplicatorUser(namespace, databaseInstance, passwordSecret),
    scanChart
  );

  const natVm = installNatVm(namespace, zone, databaseInstance);
  const dataset = installBigqueryDataset(bigQueryConfig);
  const pcc = installPrivateConnectivityConfiguration(namespace);
  const destinationProfile = installBigqueryConnectionProfile(namespace, dataset, pcc);
  const sourceProfile = installPostgresConnectionProfile(
    namespace,
    databaseInstance,
    scanChart,
    natVm,
    pcc,
    passwordSecret
  );
  installDatastreamToNatVmFirewallRule(namespace, pcc, natVm);
  installDatastream(
    namespace,
    databaseInstance,
    sourceProfile,
    destinationProfile,
    dataset,
    pubRepSlots
  );

  return {
    datasetId: dataset.id,
  };
}

export type ScanBigQueryArgs = {
  namespace: ExactNamespace;
  bigQueryConfig: ScanBigQueryConfig;
  scanReference: ScanReference;
};

type ScanReference =
  | {
      type: 'local';
      databaseInstance: gcp.sql.DatabaseInstance;
      chart: InstalledHelmChart;
    }
  | {
      type: 'external';
      databaseInstanceNamePrefix: string;
    };

export type ScanBigQuery = {
  datasetId: pulumi.Output<string>;
};

async function getScanDb(
  instanceNamePrefix: string,
  zone: string
): Promise<gcp.sql.DatabaseInstance> {
  const result = await gcp.sql.getDatabaseInstances({
    project: GCP_PROJECT,
    region: GCP_REGION,
    zone,
  });
  const instanceName =
    result.instances.find(instance => instance.name.startsWith(instanceNamePrefix))?.name ??
    (() => {
      throw new Error(
        `Could not find SV apps database instance with prefix: ${instanceNamePrefix}`
      );
    })();
  return gcp.sql.DatabaseInstance.get(instanceNamePrefix, instanceName);
}

function getLegacyParentUrn(namespace: ExactNamespace): pulumi.URN {
  return `urn:pulumi:canton-network.${CLUSTER_BASENAME}::canton-network::canton:cloud:postgres::${namespace.logicalName}-cn-apps-pg`;
}

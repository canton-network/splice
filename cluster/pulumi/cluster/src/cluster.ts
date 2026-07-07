// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import { config, GCP_PROJECT } from '@canton-network/splice-pulumi-common';

export function installCluster(): gcp.container.Cluster {
  const clusterName = `cn-${config.requireEnv('GCP_CLUSTER_BASENAME')}net`;
  const masterIpv4Cidr = config.requireEnv('GCP_MASTER_IPV4_CIDR');
  const zone = config.optionalEnv('CLOUDSDK_COMPUTE_ZONE');
  const region = config.requireEnv('CLOUDSDK_COMPUTE_REGION');

  const location = zone ? zone : region;
  const nodeLocations = zone ? undefined : [`${region}-a`];

  const cluster = new gcp.container.Cluster(
    clusterName,
    {
      name: clusterName,
      location: location,
      nodeLocations: nodeLocations,
      network: `projects/${GCP_PROJECT}/global/networks/default`,
      subnetwork: `projects/${GCP_PROJECT}/regions/${region}/subnetworks/${clusterName}-subnet`,
      networkingMode: 'VPC_NATIVE',
      datapathProvider: 'ADVANCED_DATAPATH',
      removeDefaultNodePool: true,
      initialNodeCount: 1,
      privateClusterConfig: {
        enablePrivateNodes: true,
        masterIpv4CidrBlock: masterIpv4Cidr,
      },
      workloadIdentityConfig: {
        workloadPool: `${GCP_PROJECT}.svc.id.goog`,
      },
      addonsConfig: {
        gcePersistentDiskCsiDriverConfig: { enabled: true },
        httpLoadBalancing: { disabled: false },
      },
      ipAllocationPolicy: {
        clusterIpv4CidrBlock: '',
        servicesIpv4CidrBlock: '',
      },
    },
    {
      import: `projects/${GCP_PROJECT}/locations/${location}/clusters/${clusterName}`,
      ignoreChanges: [
        'ipAllocationPolicy',
        'masterAuthorizedNetworksConfig',
        'nodePools',
        'privateClusterConfig.masterGlobalAccessConfig',
        'defaultMaxPodsConstraint',
        'maintenancePolicy',
        'nodeConfig',
        'addonsConfig',
        'releaseChannel',
        'networkConfig',
        'resourceLabels',
        'datapathProvider',
        'workloadIdentityConfig',
      ],
      protect: true,
    }
  );

  return cluster;
}

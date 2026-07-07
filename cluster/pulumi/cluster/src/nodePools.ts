// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import { config, GCP_PROJECT } from '@canton-network/splice-pulumi-common';

import { hyperdiskSupportConfig } from '../../common/src/config/hyperdiskSupportConfig';
import { gkeClusterConfig, GkeNodePoolConfig } from './config';

export async function installNodePools(): Promise<void> {
  const clusterName = `cn-${config.requireEnv('GCP_CLUSTER_BASENAME')}net`;
  const cluster = config.optionalEnv('CLOUDSDK_COMPUTE_ZONE')
    ? `projects/${GCP_PROJECT}/locations/${config.requireEnv('CLOUDSDK_COMPUTE_ZONE')}/clusters/${clusterName}`
    : clusterName;
  const zones = await gcp.compute.getZones({
    region: config.requireEnv('CLOUDSDK_COMPUTE_REGION'),
  });

  installAppsNodePools(cluster, zones.names, [
    gkeClusterConfig.nodePools.apps,
    ...gkeClusterConfig.nodePools.additionalApps,
  ]);

  const nodePoolComputeZone = config.optionalEnv('CLOUDSDK_NODEPOOL_COMPUTE_ZONE');
  new gcp.container.NodePool(
    'cn-infra-node-pool',
    {
      cluster,
      nodeConfig: {
        machineType: gkeClusterConfig.nodePools.infra.nodeType,
        taints: [
          {
            effect: 'NO_SCHEDULE',
            key: 'cn_infra',
            value: 'true',
          },
        ],
        labels: {
          cn_infra: 'true',
        },
        loggingVariant: 'DEFAULT',
      },
      nodeLocations: nodePoolComputeZone ? [nodePoolComputeZone] : undefined,
      initialNodeCount: 1,
      autoscaling: {
        minNodeCount: gkeClusterConfig.nodePools.infra.minNodes,
        maxNodeCount: gkeClusterConfig.nodePools.infra.maxNodes,
      },
    },
    {
      replaceOnChanges: ['nodeConfig.machineType'],
    }
  );

  new gcp.container.NodePool('gke-node-pool', {
    cluster,
    nodeConfig: {
      machineType: 'e2-standard-4',
      taints: [
        {
          effect: 'NO_SCHEDULE',
          key: 'components.gke.io/gke-managed-components',
          value: 'true',
        },
      ],
      loggingVariant: 'DEFAULT',
    },
    nodeLocations: nodePoolComputeZone ? [nodePoolComputeZone] : undefined,
    initialNodeCount: 1,
    autoscaling: {
      minNodeCount: 1,
      maxNodeCount: 3,
    },
  });
}

function installAppsNodePools(
  cluster: string,
  allZones: string[],
  configs: Array<GkeNodePoolConfig>
): Array<gcp.container.NodePool> {
  const nodepoolLocation = config.optionalEnv('CLOUDSDK_HYPERDISK_NODEPOOL_COMPUTE_ZONE');
  return configs.map((config, index) => {
    const zones =
      config.zones === '*'
        ? allZones
        : (config.zones ?? (nodepoolLocation !== undefined ? [nodepoolLocation] : undefined));
    if (hyperdiskSupportConfig.hyperdiskSupport.enabled) {
      return hyperdiskNodePool(index, cluster, zones, config);
    } else {
      return appsNodePool(index, cluster, zones, config);
    }
  });
}

function hyperdiskNodePool(
  index: number,
  cluster: string,
  zones: string[] | undefined,
  config: GkeNodePoolConfig
): gcp.container.NodePool {
  const name =
    index === 0
      ? 'cn-apps-node-pool-hd' // for backwards compat
      : `cn-apps-node-pool-${index}-hd`;
  return new gcp.container.NodePool(name, {
    cluster,
    nodeConfig: {
      machineType: config.nodeType,
      bootDisk: {
        diskType: 'hyperdisk-balanced',
        sizeGb: config.bootDiskSizeGb || 100,
      },
      taints: [
        {
          effect: 'NO_SCHEDULE',
          key: 'cn_apps',
          value: 'true',
        },
      ],
      labels: {
        cn_apps: 'hyperdisk',
      },
      loggingVariant: 'DEFAULT',
    },
    nodeLocations: zones,
    initialNodeCount: 0,
    autoscaling: {
      locationPolicy: 'ANY',
      minNodeCount: config.minNodes,
      maxNodeCount: config.maxNodes,
    },
  });
}
function appsNodePool(
  index: number,
  cluster: string,
  zones: string[] | undefined,
  appsNodePoolConfig: GkeNodePoolConfig
): gcp.container.NodePool {
  const name =
    index === 0
      ? 'cn-apps-node-pool' // for backwards compat
      : `cn-apps-node-pool-${index}`;
  return new gcp.container.NodePool(name, {
    cluster,
    nodeConfig: {
      machineType: appsNodePoolConfig.nodeType,
      taints: [
        {
          effect: 'NO_SCHEDULE',
          key: 'cn_apps',
          value: 'true',
        },
      ],
      labels: {
        cn_apps: 'standard',
      },
      loggingVariant: 'DEFAULT',
    },
    initialNodeCount: 0,
    autoscaling: {
      locationPolicy: 'ANY',
      minNodeCount: appsNodePoolConfig.minNodes,
      maxNodeCount: appsNodePoolConfig.maxNodes,
    },
  });
}

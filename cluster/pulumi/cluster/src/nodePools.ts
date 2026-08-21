// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import {
  config,
  GCP_PROJECT,
  appsComputeClassName,
  infraComputeClassName,
  useComputeClasses,
} from '@canton-network/splice-pulumi-common';

import { gkeClusterConfig, GkeNodePoolConfig } from './config';

export async function installNodePools(): Promise<void> {
  const clusterName = `cn-${config.requireEnv('GCP_CLUSTER_BASENAME')}net`;
  const cluster = config.optionalEnv('CLOUDSDK_COMPUTE_ZONE')
    ? `projects/${GCP_PROJECT}/locations/${config.requireEnv('CLOUDSDK_COMPUTE_ZONE')}/clusters/${clusterName}`
    : clusterName;
  const zones = await gcp.compute.getZones({
    region: config.requireEnv('CLOUDSDK_COMPUTE_REGION'),
  });
  const nodePoolComputeZone = config.optionalEnv('CLOUDSDK_NODEPOOL_COMPUTE_ZONE');

  const appPools = installAppsNodePools(cluster, zones.names, [
    gkeClusterConfig.nodePools.apps,
    ...gkeClusterConfig.nodePools.additionalApps,
  ]);
  const infraPools = installInfraNodePools(cluster, zones.names, nodePoolComputeZone, [
    gkeClusterConfig.nodePools.infra,
    ...gkeClusterConfig.nodePools.additionalInfra,
  ]);

  new gcp.container.NodePool(
    'gke-node-pool',
    {
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
    },
    {
      replaceOnChanges: ['nodeConfig.machineType'],
    }
  );

  if (useComputeClasses) {
    installComputeClass(appsComputeClassName, appPools);
    installComputeClass(infraComputeClassName, infraPools);
  }
}

type NodeConfigLabelsAndTaints = Pick<
  gcp.types.input.container.NodePoolNodeConfig,
  'labels' | 'taints'
>;

function installAppsNodePools(
  cluster: string,
  allZones: string[],
  configs: Array<GkeNodePoolConfig>
): Array<gcp.container.NodePool> {
  const defaultZone = config.optionalEnv('CLOUDSDK_HYPERDISK_NODEPOOL_COMPUTE_ZONE');
  return configs.map((config, index) => {
    const name =
      index === 0
        ? 'cn-apps-node-pool-hd' // for backwards compat
        : `cn-apps-node-pool-${index}-hd`;
    // With ComputeClasses, we rely on the `cloud.google.com/compute-class` label only.
    // That label *must* be present for the ComputeClass use the node pool, even
    // if it's explicitly mentioned in the ComputeClass priorities.
    const labelsAndTaints: NodeConfigLabelsAndTaints = useComputeClasses
      ? {
          taints: [
            {
              effect: 'NO_SCHEDULE',
              key: 'cloud.google.com/compute-class',
              value: appsComputeClassName,
            },
          ],
          labels: {
            'cloud.google.com/compute-class': appsComputeClassName,
            ...config.labels,
          },
        }
      : {
          taints: [
            {
              effect: 'NO_SCHEDULE',
              key: 'cn_apps',
              value: 'true',
            },
          ],
          labels: {
            cn_apps: 'hyperdisk',
            ...config.labels,
          },
        };
    return new gcp.container.NodePool(
      name,
      {
        cluster,
        nodeConfig: {
          machineType: config.nodeType,
          bootDisk: {
            diskType: 'hyperdisk-balanced',
            sizeGb: config.bootDiskSizeGb || 100,
          },
          ...labelsAndTaints,
          loggingVariant: 'DEFAULT',
        },
        nodeLocations:
          config.zones === '*'
            ? allZones
            : (config.zones ?? (defaultZone !== undefined ? [defaultZone] : undefined)),
        initialNodeCount: 0,
        autoscaling: autoscalingConfigOf(config),
      },
      {
        replaceOnChanges: ['nodeConfig.machineType'],
      }
    );
  });
}

function installInfraNodePools(
  cluster: string,
  allZones: string[],
  defaultZone: string | undefined,
  configs: Array<GkeNodePoolConfig>
): Array<gcp.container.NodePool> {
  return configs.map((config, index) => {
    const name =
      index === 0
        ? 'cn-infra-node-pool' // for backwards compat
        : `cn-infra-node-pool-${index}`;

    // With ComputeClasses, we rely on the `cloud.google.com/compute-class` label only.
    // That label *must* be present for the ComputeClass use the node pool, even
    // if it's explicitly mentioned in the ComputeClass priorities.
    const labelsAndTaints: NodeConfigLabelsAndTaints = useComputeClasses
      ? {
          taints: [
            {
              effect: 'NO_SCHEDULE',
              key: 'cloud.google.com/compute-class',
              value: infraComputeClassName,
            },
          ],
          labels: {
            'cloud.google.com/compute-class': infraComputeClassName,
            ...config.labels,
          },
        }
      : {
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
        };

    return new gcp.container.NodePool(
      name,
      {
        cluster,
        nodeConfig: {
          machineType: config.nodeType,
          ...labelsAndTaints,
          loggingVariant: 'DEFAULT',
        },
        nodeLocations:
          config.zones === '*'
            ? allZones
            : (config.zones ?? (defaultZone !== undefined ? [defaultZone] : undefined)),
        initialNodeCount: 1,
        autoscaling: autoscalingConfigOf(config),
      },
      {
        replaceOnChanges: ['nodeConfig.machineType'],
      }
    );
  });
}

function installComputeClass(
  name: string,
  pools: gcp.container.NodePool[]
): k8s.apiextensions.CustomResource {
  return new k8s.apiextensions.CustomResource(
    `compute-class-${name}`,
    {
      apiVersion: 'cloud.google.com/v1',
      kind: 'ComputeClass',
      metadata: { name },
      spec: {
        // Reference the existing, manually-managed node pools rather than
        // letting GKE auto-provision. Priorities are evaluated in order.
        priorities: pools.map(p => ({ nodepools: [p.name] })),
        nodePoolAutoCreation: { enabled: false },
      },
    },
    {
      dependsOn: pools,
    }
  );
}

function autoscalingConfigOf(config: GkeNodePoolConfig): gcp.container.NodePoolArgs['autoscaling'] {
  return {
    // Location policy decides how nodes are allocated across zones when more then one zone is configured.
    // By default it is set to BALANCED, which is useful in HA scenarios. We configure multiple zones on
    // scratchnets and in CI to get better availability of compute resources so ANY is more suitable.
    // For single-zone clusters, which includes prod clusters, this doesn't matter.
    locationPolicy: 'ANY',
    minNodeCount: config.minNodes,
    maxNodeCount: config.maxNodes,
  };
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import {
  CLUSTER_NAME,
  GCP_PROJECT,
  GCP_REGION,
  GCP_ZONE,
} from '@canton-network/splice-pulumi-common';

// The GKE cluster itself is still created by `cncluster create` and some of its settings are changed with
// `cncluster cluster_*` subcommands. For now, this only imports the existing cluster into the Pulumi state without
// changing it, so that its settings can be moved into Pulumi one at a time.
// TODO(DACH-NY/canton-network-internal#417) Manage the cluster settings here instead of in cncluster.

// All cluster inputs except `name`, `project` and `location`, which identify the cluster, and except `nodeConfig` and
// `nodePools`: GKE returns an empty `windowsNodeConfig.osversion` in them, which fails the provider's validation when
// ignoreChanges copies it into the inputs (https://github.com/pulumi/pulumi-terraform-bridge/issues/3456). Leaving
// them unset does not cause a diff since they are optional and computed; the node pools are managed in nodePools.ts.
// Remove entries from this list as the corresponding settings are moved into Pulumi.
const ignoredClusterInputs: (keyof gcp.container.ClusterArgs)[] = [
  'addonsConfig',
  'allowNetAdmin',
  'anonymousAuthenticationConfig',
  'authenticatorGroupsConfig',
  'binaryAuthorization',
  'clusterAutoscaling',
  'clusterIpv4Cidr',
  'clusterTelemetry',
  'confidentialNodes',
  'controlPlaneEndpointsConfig',
  'costManagementConfig',
  'databaseEncryption',
  'datapathProvider',
  'defaultMaxPodsPerNode',
  'defaultSnatStatus',
  'deletionProtection',
  'description',
  'disableL4LbFirewallReconciliation',
  'dnsConfig',
  'enableAutopilot',
  'enableCiliumClusterwideNetworkPolicy',
  'enableFqdnNetworkPolicy',
  'enableIntranodeVisibility',
  'enableK8sBetaApis',
  'enableKubernetesAlpha',
  'enableL4IlbSubsetting',
  'enableLegacyAbac',
  'enableMultiNetworking',
  'enableShieldedNodes',
  'enableTpu',
  'enterpriseConfig',
  'fleet',
  'gatewayApiConfig',
  'gkeAutoUpgradeConfig',
  'identityServiceConfig',
  'inTransitEncryptionConfig',
  'initialNodeCount',
  'ipAllocationPolicy',
  'loggingConfig',
  'loggingService',
  'maintenancePolicy',
  'managedOpentelemetryConfig',
  'masterAuth',
  'masterAuthorizedNetworksConfig',
  'meshCertificates',
  'minMasterVersion',
  'monitoringConfig',
  'monitoringService',
  'network',
  'networkPerformanceConfig',
  'networkPolicy',
  'networkingMode',
  'nodeLocations',
  'nodePoolAutoConfig',
  'nodePoolDefaults',
  'nodeVersion',
  'notificationConfig',
  'podAutoscaling',
  'podSecurityPolicyConfig',
  'privateClusterConfig',
  'privateIpv6GoogleAccess',
  'protectConfig',
  'rbacBindingConfig',
  'releaseChannel',
  'removeDefaultNodePool',
  'resourceLabels',
  'resourceUsageExportConfig',
  'secretManagerConfig',
  'secretSyncConfig',
  'securityPostureConfig',
  'serviceExternalIpsConfig',
  'subnetwork',
  'tpuConfig',
  'userManagedKeysConfig',
  'verticalPodAutoscaling',
  'workloadAltsConfig',
  'workloadIdentityConfig',
];

export function installGkeCluster(): gcp.container.Cluster {
  // Same location as `cncluster create` uses: the zone if set, otherwise the region.
  const location = GCP_ZONE || GCP_REGION;
  return new gcp.container.Cluster(
    'gke-cluster',
    {
      name: CLUSTER_NAME,
      project: GCP_PROJECT,
      location,
    },
    {
      // Only takes effect if the cluster is not yet in the stack's state.
      import: `projects/${GCP_PROJECT}/locations/${location}/clusters/${CLUSTER_NAME}`,
      // Take the inputs from the existing cluster: nothing is changed yet.
      ignoreChanges: ignoredClusterInputs,
      // Never delete or replace the cluster through Pulumi.
      protect: true,
      retainOnDelete: true,
    }
  );
}

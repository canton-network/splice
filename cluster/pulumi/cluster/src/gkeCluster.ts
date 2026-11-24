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

// The cluster inputs that are not ignored:
// - `name`, `project` and `location`, which identify the cluster.
// - `nodeConfig` and `nodePools`: GKE returns an empty `windowsNodeConfig.osversion` in them, which fails the
//   provider's validation when ignoreChanges copies it into the inputs
//   (https://github.com/pulumi/pulumi-terraform-bridge/issues/3456). Leaving them unset does not cause a diff since
//   they are optional and computed; the node pools are managed in nodePools.ts.
// Add inputs here as the corresponding settings are moved into Pulumi.
type NotIgnoredClusterInput = 'name' | 'project' | 'location' | 'nodeConfig' | 'nodePools';

// All other cluster inputs are ignored, i.e. taken from the existing cluster. The type makes this fail to compile if
// an input is missing or no longer exists, e.g. after a bump of @pulumi/gcp: an input that is not ignored and has a
// provider default would otherwise change the live cluster. Add new inputs here unless they should be managed.
const ignoredClusterInputs = {
  addonsConfig: true,
  allowNetAdmin: true,
  anonymousAuthenticationConfig: true,
  authenticatorGroupsConfig: true,
  binaryAuthorization: true,
  clusterAutoscaling: true,
  clusterIpv4Cidr: true,
  clusterTelemetry: true,
  confidentialNodes: true,
  controlPlaneEndpointsConfig: true,
  costManagementConfig: true,
  databaseEncryption: true,
  datapathProvider: true,
  defaultMaxPodsPerNode: true,
  defaultSnatStatus: true,
  deletionProtection: true,
  description: true,
  disableL4LbFirewallReconciliation: true,
  dnsConfig: true,
  enableAutopilot: true,
  enableCiliumClusterwideNetworkPolicy: true,
  enableFqdnNetworkPolicy: true,
  enableIntranodeVisibility: true,
  enableK8sBetaApis: true,
  enableKubernetesAlpha: true,
  enableL4IlbSubsetting: true,
  enableLegacyAbac: true,
  enableMultiNetworking: true,
  enableShieldedNodes: true,
  enableTpu: true,
  enterpriseConfig: true,
  fleet: true,
  gatewayApiConfig: true,
  gkeAutoUpgradeConfig: true,
  identityServiceConfig: true,
  inTransitEncryptionConfig: true,
  initialNodeCount: true,
  ipAllocationPolicy: true,
  loggingConfig: true,
  loggingService: true,
  maintenancePolicy: true,
  managedOpentelemetryConfig: true,
  masterAuth: true,
  masterAuthorizedNetworksConfig: true,
  meshCertificates: true,
  minMasterVersion: true,
  monitoringConfig: true,
  monitoringService: true,
  network: true,
  networkPerformanceConfig: true,
  networkPolicy: true,
  networkingMode: true,
  nodeLocations: true,
  nodePoolAutoConfig: true,
  nodePoolDefaults: true,
  nodeVersion: true,
  notificationConfig: true,
  podAutoscaling: true,
  podSecurityPolicyConfig: true,
  privateClusterConfig: true,
  privateIpv6GoogleAccess: true,
  protectConfig: true,
  rbacBindingConfig: true,
  releaseChannel: true,
  removeDefaultNodePool: true,
  resourceLabels: true,
  resourceUsageExportConfig: true,
  secretManagerConfig: true,
  secretSyncConfig: true,
  securityPostureConfig: true,
  serviceExternalIpsConfig: true,
  subnetwork: true,
  tpuConfig: true,
  userManagedKeysConfig: true,
  verticalPodAutoscaling: true,
  workloadAltsConfig: true,
  workloadIdentityConfig: true,
} satisfies Record<Exclude<keyof gcp.container.ClusterArgs, NotIgnoredClusterInput>, true>;

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
      ignoreChanges: Object.keys(ignoredClusterInputs),
      // Never delete or replace the cluster through Pulumi.
      protect: true,
      retainOnDelete: true,
    }
  );
}

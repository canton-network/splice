// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as _ from 'lodash';
import { Release } from '@pulumi/kubernetes/helm/v3';
import path from 'path';

import { CnChartVersion } from './artifacts';
import { config, imagePullPolicy } from './config';
import { spliceConfig } from './config/config';
import { activeVersion } from './domainMigration';
import { SplicePlaceholderResource } from './pulumiUtilResources';
import {
  ChartValues,
  CLUSTER_HOSTNAME,
  CLUSTER_NAME,
  DOCKER_REPO,
  ExactNamespace,
  fixedTokens,
  HELM_CHART_TIMEOUT_SEC,
  HELM_MAX_HISTORY_SIZE,
  HELM_REPO,
  loadJsonFromFile,
  loadYamlFromFile,
  SPLICE_ROOT,
} from './utils';

export type InstalledHelmChart = Release | SplicePlaceholderResource;

// The default type of dependsOn is an unworkable abomination.
export type SpliceCustomResourceOptions = Omit<pulumi.CustomResourceOptions, 'dependsOn'> & {
  dependsOn?: pulumi.Input<pulumi.Resource>[];
};

export function withAddedDependencies(
  opts?: SpliceCustomResourceOptions,
  extraDependsOn?: pulumi.Input<pulumi.Resource>[]
): SpliceCustomResourceOptions {
  return opts
    ? {
        ...opts,
        dependsOn: opts.dependsOn?.concat(extraDependsOn || []),
      }
    : { dependsOn: extraDependsOn };
}

// pulumi.Input<T> allows Promise<T>, which can cause issues with our deployment scripts (i.e. auth0 token cache)
// if not awaited. this custom type is a subset that excludes promises, which gives us some type safety
export type CnInput<T> = T | pulumi.OutputInstance<T>;

const versionsFile: string | undefined = config.optionalEnv('IMAGE_VERSIONS_FILE');
const versionsFromFile: undefined | { [key: string]: { [key: string]: string } } =
  versionsFile && loadJsonFromFile(versionsFile);

function getVersionOverrideFromVersionsFile(
  nsLogicalName: string,
  chartName: string
): string | undefined {
  return (
    versionsFromFile &&
    versionsFromFile[nsLogicalName] &&
    versionsFromFile[nsLogicalName][chartName]
  );
}

function installSpliceHelmChartByNamespaceName(
  nsLogicalName: string,
  nsMetadataName: pulumi.Output<string>,
  name: string,
  chartName: string,
  values: ChartValues = {},
  version: CnChartVersion = activeVersion,
  opts?: SpliceCustomResourceOptions,
  includeNamespaceInName = true,
  affinityAndTolerations: object = appsKubernetesScheduling,
  timeout: number = HELM_CHART_TIMEOUT_SEC
): InstalledHelmChart {
  if (spliceConfig.pulumiProjectConfig.installDataOnly) {
    return new SplicePlaceholderResource(name);
  } else {
    return new k8s.helm.v3.Release(
      includeNamespaceInName ? `${nsLogicalName}-${name}` : name,
      {
        name,
        namespace: nsMetadataName,
        chart: chartPath(chartName, version),
        version: versionStringWithPossibleOverride(version, nsLogicalName, chartName),
        values: {
          ...cnChartValues(version, chartName, values),
          ...affinityAndTolerations,
          ...imagePullPolicy,
        },
        timeout,
        maxHistory: HELM_MAX_HISTORY_SIZE,
      },
      opts
    );
  }
}

export function installSpliceHelmChart(
  xns: ExactNamespace,
  name: string,
  chartName: string,
  values: ChartValues = {},
  version: CnChartVersion = activeVersion,
  opts?: SpliceCustomResourceOptions,
  includeNamespaceInName = true,
  affinityAndTolerations: object = appsKubernetesScheduling,
  timeout: number = HELM_CHART_TIMEOUT_SEC
): InstalledHelmChart {
  return installSpliceHelmChartByNamespaceName(
    xns.logicalName,
    xns.ns.metadata.name,
    name,
    chartName,
    values,
    version,
    opts,
    includeNamespaceInName,
    affinityAndTolerations,
    timeout
  );
}

function cnChartValues(
  version: CnChartVersion,
  chartName: string,
  overrideValues: ChartValues = {}
): ChartValues {
  // This is useful for the `expected` jsons but functionally redundant, so we only do this when using local charts
  const chartDefaultValues =
    version.type === 'local' ? loadYamlFromFile(`${chartPath(chartName, version)}values.yaml`) : {};

  return _.mergeWith(
    {},
    chartDefaultValues,
    {
      imageRepo: DOCKER_REPO,
      cluster: {
        hostname: CLUSTER_HOSTNAME,
        name: CLUSTER_NAME,
        fixedTokens: fixedTokens(),
        dnsName: CLUSTER_HOSTNAME,
      },
    },
    overrideValues,
    (a, b) => (_.isArray(b) ? b : undefined)
  );
}

export function installSpliceRunbookHelmChartByNamespaceName(
  nsMetadataName: pulumi.Output<string> | string,
  nsLogicalName: string,
  name: string,
  chartName: string,
  values: ChartValues,
  version: CnChartVersion = activeVersion,
  opts?: SpliceCustomResourceOptions,
  timeout: number = HELM_CHART_TIMEOUT_SEC
): InstalledHelmChart {
  if (spliceConfig.pulumiProjectConfig.installDataOnly) {
    return new SplicePlaceholderResource(name);
  } else {
    return new k8s.helm.v3.Release(
      name,
      {
        name: name,
        namespace: nsMetadataName,
        chart: chartPath(chartName, version),
        version: versionStringWithPossibleOverride(version, nsLogicalName, chartName),
        values: {
          ...appsKubernetesScheduling,
          ...values,
          imageRepo: DOCKER_REPO,
          ...imagePullPolicy,
        },
        timeout,
        maxHistory: HELM_MAX_HISTORY_SIZE,
      },
      opts
    );
  }
}

export function installSpliceRunbookHelmChart(
  ns: ExactNamespace,
  name: string,
  chartName: string,
  values: ChartValues,
  version: CnChartVersion = activeVersion,
  opts?: SpliceCustomResourceOptions,
  timeout: number = HELM_CHART_TIMEOUT_SEC
): InstalledHelmChart {
  return installSpliceRunbookHelmChartByNamespaceName(
    ns.ns.metadata.name,
    ns.logicalName,
    name,
    chartName,
    values,
    version,
    { ...opts, dependsOn: opts?.dependsOn?.concat([ns.ns]) || [] },
    timeout
  );
}

export function chartPath(chartName: string, version: CnChartVersion): string {
  return version.type === 'local'
    ? `${path.relative(process.cwd(), SPLICE_ROOT)}/cluster/helm/${chartName}/`
    : `${HELM_REPO}/${chartName}`;
}

function versionStringWithPossibleOverride(
  version: CnChartVersion,
  nsLogicalName: string,
  chartPath: string
) {
  if (version.type === 'local') {
    return undefined;
  } else {
    const versionOverride = getVersionOverrideFromVersionsFile(nsLogicalName, chartPath);
    return versionOverride || version.version;
  }
}

export const appsComputeClassName = 'cn-apps';
export const infraComputeClassName = 'cn-infra';

const appsKubernetesSchedulingComputeClass = {
  // A nodeSelector is a simpler way to select a single ComputeClass.
  // We still use nodeAffinity for better compatibility with other parts of the code.
  // As long as the node affinity is a single term, it *should* trigger the ComputeClass
  // mechanism for scaling up nodes.
  // nodeSelector: {'cloud.google.com/compute-class': appsComputeClassName},
  affinity: {
    nodeAffinity: {
      requiredDuringSchedulingIgnoredDuringExecution: {
        nodeSelectorTerms: [
          {
            matchExpressions: [
              {
                key: 'cloud.google.com/compute-class',
                operator: 'In',
                values: [appsComputeClassName],
              },
            ],
          },
        ],
      },
    },
  },
  tolerations: [{ key: 'cn_apps', operator: 'Exists', effect: 'NoSchedule' }],
};

const appsKubernetesSchedulingAffinityTolerations = {
  affinity: {
    nodeAffinity: {
      requiredDuringSchedulingIgnoredDuringExecution: {
        nodeSelectorTerms: [
          {
            matchExpressions: [
              {
                key: 'cn_apps',
                operator: 'Exists',
              },
              {
                key: 'cn_apps',
                operator: 'In',
                values: ['hyperdisk'],
              },
            ],
          },
        ],
      },
    },
  },
  tolerations: [
    {
      key: 'cn_apps',
      operator: 'Exists',
      effect: 'NoSchedule',
    },
  ],
};

export const useComputeClasses =
  spliceConfig.configuration.kubernetesScheduling.computeClasses.enabled;

// Values that determine how apps pods are scheduled.
export const appsKubernetesScheduling = useComputeClasses
  ? appsKubernetesSchedulingComputeClass
  : appsKubernetesSchedulingAffinityTolerations;

export const infraKubernetesSchedulingComputeClass = {
  // A nodeSelector is a simpler way to select a single ComputeClass.
  // We still use nodeAffinity for better compatibility with other parts of the code.
  // As long as the node affinity is a single term, it *should* trigger the ComputeClass
  // mechanism for scaling up nodes.
  // nodeSelector: {'cloud.google.com/compute-class': infraComputeClassName},
  affinity: {
    nodeAffinity: {
      requiredDuringSchedulingIgnoredDuringExecution: {
        nodeSelectorTerms: [
          {
            matchExpressions: [
              {
                key: 'cloud.google.com/compute-class',
                operator: 'In',
                values: [infraComputeClassName],
              },
            ],
          },
        ],
      },
    },
  },
  tolerations: [{ key: 'cn_infra', operator: 'Exists', effect: 'NoSchedule' }],
};

export const infraKubernetesSchedulingAffinityTolerations = {
  affinity: {
    nodeAffinity: {
      requiredDuringSchedulingIgnoredDuringExecution: {
        nodeSelectorTerms: [
          {
            matchExpressions: [
              {
                key: 'cn_infra',
                operator: 'Exists',
              },
            ],
          },
        ],
      },
    },
  },
  tolerations: [
    {
      key: 'cn_infra',
      operator: 'Exists',
      effect: 'NoSchedule',
    },
  ],
};

// Values that determine how infra pods are scheduled.
export const infraKubernetesScheduling = useComputeClasses
  ? infraKubernetesSchedulingComputeClass
  : infraKubernetesSchedulingAffinityTolerations;

// Values that determine how daemons are scheduled that need to run on BOTH apps and infra nodes.
export const infraAndAppsKubernetesSchedulingForDaemonSets = useComputeClasses
  ? {
      // A pod can only pick a single ComputeClass via a nodeSelector,
      // so we use nodeAffinity that allows OR-expressions.
      // This is fine because DaemonSets do not trigger autoscaling and are instead scheduled once
      // per existing, eligible node.
      affinity: {
        nodeAffinity: {
          requiredDuringSchedulingIgnoredDuringExecution: {
            nodeSelectorTerms: [
              {
                matchExpressions: [
                  {
                    key: 'cloud.google.com/compute-class',
                    operator: 'In',
                    values: [appsComputeClassName, infraComputeClassName],
                  },
                ],
              },
            ],
          },
        },
      },
      tolerations: [
        ...appsKubernetesSchedulingComputeClass.tolerations,
        ...infraKubernetesSchedulingComputeClass.tolerations,
      ],
    }
  : {
      affinity: {
        nodeAffinity: {
          requiredDuringSchedulingIgnoredDuringExecution: {
            nodeSelectorTerms: [
              ...appsKubernetesSchedulingAffinityTolerations.affinity.nodeAffinity
                .requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms,
              ...infraKubernetesSchedulingAffinityTolerations.affinity.nodeAffinity
                .requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms,
            ],
          },
        },
      },
      tolerations: [
        ...appsKubernetesSchedulingAffinityTolerations.tolerations,
        ...infraKubernetesSchedulingAffinityTolerations.tolerations,
      ],
    };

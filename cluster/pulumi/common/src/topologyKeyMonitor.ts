// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';

import { CnChartVersion } from './artifacts';
import { TopologyKeyMonitorConfig } from './config/topologyKeyMonitorConfig';
import {
  CnInput,
  InstalledHelmChart,
  installSpliceHelmChart,
  SpliceCustomResourceOptions,
} from './helm';
import { ExactNamespace } from './namespace';

export function installTopologyKeyMonitor(
  xns: ExactNamespace,
  participantAddress: CnInput<string>,
  config: TopologyKeyMonitorConfig,
  version: CnChartVersion,
  serviceAccountName?: string,
  opts?: SpliceCustomResourceOptions
): InstalledHelmChart | undefined {
  if (!config.enable) {
    return undefined;
  }
  if (config.keys.length === 0) {
    void pulumi.log.info(
      `Topology key monitor is enabled for ${xns.logicalName} but no keys are configured. Skipping...`
    );
    return undefined;
  }
  void pulumi.log.info(`Installing topology key monitor in ${xns.logicalName}`);
  return installSpliceHelmChart(
    xns,
    'topology-key-monitor',
    'splice-topology-key-monitor',
    {
      participant: {
        address: participantAddress,
      },
      keys: config.keys,
      pollIntervalSeconds: config.pollIntervalSeconds,
      ...(config.resources ? { resources: config.resources } : {}),
      ...(serviceAccountName ? { serviceAccountName } : {}),
    },
    config.chartVersion ? CnChartVersion.parse(config.chartVersion) : version,
    opts
  );
}

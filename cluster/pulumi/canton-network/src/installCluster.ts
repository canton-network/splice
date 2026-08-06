// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
  config,
  DecentralizedSynchronizerUpgradeConfig,
  isDevNet,
  spliceConfig,
} from '@canton-network/splice-pulumi-common';
import { Resource } from '@pulumi/pulumi';

import { activeVersion } from '../../common';
import { installChaosMesh } from './chaosMesh';
import { installDocs } from './docs';
import { Dso } from './dso';

/// Toplevel Chart Installs

console.error(`Launching with isDevNet: ${isDevNet}`);

const enableChaosMesh = config.envFlag('ENABLE_CHAOS_MESH');

export async function installCluster(auth0Client: Auth0Client): Promise<void> {
  console.error(
    activeVersion.type === 'local'
      ? 'Using locally built charts by default'
      : `Using charts from the container registry by default, version ${activeVersion.version}`
  );

  const dso = spliceConfig.configuration.synchronizerMigration.splitSvDeploymentEnabled
    ? undefined
    : new Dso('dso', {
        auth0Client,
        decentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerUpgradeConfig,
      });

  const allSvs = (await dso?.allSvs) ?? [];

  const svDependencies = allSvs.flatMap(sv => [sv.scan, sv.svApp, sv.validatorApp, sv.ingress]);

  installDocs();

  if (enableChaosMesh) {
    installChaosMesh({ dependsOn: svDependencies });
  }
}

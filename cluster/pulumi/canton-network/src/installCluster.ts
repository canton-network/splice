// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
  config,
  DecentralizedSynchronizerUpgradeConfig,
  exactNamespace,
  isDevNet,
  spliceConfig,
} from '@canton-network/splice-pulumi-common';
import { configForSv, coreSvsToDeploy } from '@canton-network/splice-pulumi-common-sv';
import { configureScanBigQuery } from '@canton-network/splice-pulumi-common-sv/src/bigQuery';
import { InstalledSv } from '@canton-network/splice-pulumi-common-sv/src/sv';
import { SplitPostgresInstances } from '@canton-network/splice-pulumi-common/src/config/configs';
import { CloudPostgres } from '@canton-network/splice-pulumi-common/src/postgres';

import { activeVersion } from '../../common';
import { installChaosMesh } from './chaosMesh';
import { installDocs } from './docs';
import { Dso } from './dso';

/// Toplevel Chart Installs

console.error(`Launching with isDevNet: ${isDevNet}`);

const enableChaosMesh = config.envFlag('ENABLE_CHAOS_MESH');

export async function installCluster(auth0Client: Auth0Client): Promise<Dso | undefined> {
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
        exportSvResources:
          spliceConfig.configuration.synchronizerMigration.migrateToSplitSvDeployment,
      });

  const sv1 = await dso?.sv1;

  await installBigQuery(sv1);

  const allSvs = (await dso?.allSvs) ?? [];

  const svDependencies = allSvs.flatMap(sv => [sv.scan, sv.svApp, sv.validatorApp, sv.ingress]);

  installDocs();

  if (enableChaosMesh) {
    installChaosMesh({ dependsOn: svDependencies });
  }

  return dso;
}

async function installBigQuery(installedSv: InstalledSv | undefined) {
  const nodeName = installedSv?.nodeName ?? coreSvsToDeploy[0].nodeName;
  const namespace = installedSv?.namespace ?? exactNamespace(nodeName, true, true);
  const config = configForSv(nodeName);
  const installedAppsPostgres = installedSv?.appsPostgres;
  const localScanReference =
    installedAppsPostgres !== undefined && installedAppsPostgres instanceof CloudPostgres
      ? {
          type: 'local' as const,
          databaseInstance: installedAppsPostgres.databaseInstance,
          chart: installedSv!.scan,
        }
      : undefined;
  const externalScanReference =
    SplitPostgresInstances &&
    (config.appsPg?.cloudSql ?? spliceConfig.pulumiProjectConfig.cloudSql).enabled
      ? {
          type: 'external' as const,
          databaseInstanceNamePrefix: `${namespace.logicalName}-cn-apps-pg`,
        }
      : undefined;
  const scanReference = localScanReference ?? externalScanReference;
  const bigQueryConfig = config.scanApp?.bigQuery;
  bigQueryConfig !== undefined && scanReference !== undefined
    ? await configureScanBigQuery(namespace, scanReference, bigQueryConfig)
    : undefined;
}

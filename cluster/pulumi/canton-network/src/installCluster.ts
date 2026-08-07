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
import {
  configureScanBigQuery,
  ScanBigQueryArgs,
} from '@canton-network/splice-pulumi-common-sv/src/bigQuery';
import { InstalledSv } from '@canton-network/splice-pulumi-common-sv/src/sv';
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

  // TODO(#6719) once all clusters have been migrated this can be removed.
  const dso = spliceConfig.configuration.synchronizerMigration.splitSvDeploymentEnabled
    ? undefined
    : new Dso('dso', {
        auth0Client,
        decentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerUpgradeConfig,
        exportSvResources:
          spliceConfig.configuration.synchronizerMigration.migrateToSplitSvDeployment,
      });

  const locallyInstalledSvs = await dso?.allSvs;
  const bigQueryArgs = [...iterateBigQueryArgs(locallyInstalledSvs)];
  if (bigQueryArgs.length > 1) {
    throw new Error(
      `Multiple SVs with BigQuery configuration found: ${bigQueryArgs.map(arg => arg.namespace.logicalName).join(', ')}`
    );
  }
  for (const args of bigQueryArgs) {
    await configureScanBigQuery(args);
  }

  const svDependencies = (locallyInstalledSvs ?? []).flatMap(sv => [
    sv.scan,
    sv.svApp,
    sv.validatorApp,
    sv.ingress,
  ]);

  installDocs();

  if (enableChaosMesh) {
    installChaosMesh({ dependsOn: svDependencies });
  }

  return dso;
}

function* iterateBigQueryArgs(
  locallyInstalledSvs?: Array<InstalledSv>
): Generator<ScanBigQueryArgs> {
  if (locallyInstalledSvs === undefined) {
    for (const sv of coreSvsToDeploy) {
      const config = configForSv(sv.nodeName);
      const bigQueryConfig = config?.scanApp?.bigQuery;
      const cloudSqlEnabled = (config.appsPg?.cloudSql ?? spliceConfig.pulumiProjectConfig.cloudSql)
        .enabled;
      if (bigQueryConfig !== undefined && cloudSqlEnabled) {
        const namespace = exactNamespace(sv.nodeName, true, true);
        yield {
          namespace,
          bigQueryConfig: bigQueryConfig,
          scanReference: {
            type: 'external',
            databaseInstanceNamePrefix: `${namespace.logicalName}-cn-apps-pg`,
          },
        };
      }
    }
  } else {
    // TODO(#6719) once all clusters have been migrated this can be removed.
    for (const sv of locallyInstalledSvs) {
      const config = configForSv(sv.nodeName);
      const bigQueryConfig = config?.scanApp?.bigQuery;
      if (bigQueryConfig !== undefined && sv.appsPostgres instanceof CloudPostgres) {
        yield {
          namespace: sv.namespace,
          bigQueryConfig: bigQueryConfig,
          scanReference: {
            type: 'local',
            databaseInstance: sv.appsPostgres.databaseInstance,
            chart: sv.scan,
          },
        };
      }
    }
  }
}

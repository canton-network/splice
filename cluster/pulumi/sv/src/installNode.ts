// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  activeVersion,
  Auth0Client,
  auth0UserNameEnvVarSource,
  exactNamespace,
  imagePullSecretWithNonDefaultServiceAccount,
  installLedgerApiUserSecret,
  spliceConfig,
} from '@canton-network/splice-pulumi-common';
import {
  configForSv,
  StaticSvConfig,
  svConfigs,
  svRunbookConfig,
} from '@canton-network/splice-pulumi-common-sv';
import {
  installSvNodeStandalone,
  MigrationArgs,
  SvsMigrationOutput,
} from '@canton-network/splice-pulumi-common-sv/src/sv';
import { StackReferences } from '@canton-network/splice-pulumi-common/src/stackReferences';

import { installParticipant } from './participant';

export async function installNode(sv: string, auth0Client: Auth0Client): Promise<void> {
  // TODO(#6719) once all clusters have been migrated hardcode splitSvDeploymentEnabled to true
  const splitSvDeploymentEnabled =
    spliceConfig.configuration.synchronizerMigration.splitSvDeploymentEnabled;
  const staticConfig = findStaticConfigOrFail(sv);
  const config = configForSv(staticConfig.nodeName);
  const xns = exactNamespace(staticConfig.nodeName, true, !splitSvDeploymentEnabled);
  const serviceAccountName = 'sv';
  const imagePullDeps = imagePullSecretWithNonDefaultServiceAccount(xns, serviceAccountName);
  const auth0Config = auth0Client.getCfg();
  const ledgerApiUserSecret = installLedgerApiUserSecret(auth0Client, xns, 'sv', 'sv');
  const ledgerApiUserSecretSource = auth0UserNameEnvVarSource('sv', true);
  // TODO(#6719) once all clusters have been migrated remove this
  const migrateToSplitSvDeployment =
    spliceConfig.configuration.synchronizerMigration.migrateToSplitSvDeployment;
  if (
    (splitSvDeploymentEnabled || migrateToSplitSvDeployment) &&
    staticConfig.nodeName !== svRunbookConfig.nodeName
  ) {
    const migrationArgs = migrateToSplitSvDeployment
      ? await getMigrationArgsForSv(staticConfig.nodeName)
      : undefined;
    await installSvNodeStandalone(xns, staticConfig, config, auth0Client, [], migrationArgs);
  }
  await installParticipant(
    {
      xns,
      participant: config.participant,
      logging: config.logging,
      auth0: auth0Config,
      version: config.versionOverride ?? activeVersion,
      disableProtection: staticConfig.nodeName === svRunbookConfig.nodeName,
      participantAdminUserNameFrom: ledgerApiUserSecretSource,
      imagePullServiceAccountName: serviceAccountName,
    },
    { dependsOn: [...imagePullDeps, ledgerApiUserSecret] }
  );
}

function findStaticConfigOrFail(sv: string): StaticSvConfig {
  const svConfig = svConfigs.concat([svRunbookConfig]).find(config => {
    return config.nodeName === sv;
  });
  if (svConfig === undefined) {
    throw new Error(`No sv config found for ${sv}`);
  } else {
    return svConfig;
  }
}

// TODO(#6719) once all clusters have been migrated remove this
async function getMigrationArgsForSv(nodeName: string): Promise<MigrationArgs> {
  const svs = (await StackReferences.cantonNetwork.requireOutputValue('svs')) as SvsMigrationOutput;
  const sv =
    svs.find(sv => sv.nodeName === nodeName) ??
    (() => {
      throw new Error(`No migration output found for SV: ${nodeName}`);
    })();
  return {
    action: 'import',
    databaseInstanceName: sv.databaseInstanceName,
    databaseSecretName: sv.databaseSecretName,
  };
}

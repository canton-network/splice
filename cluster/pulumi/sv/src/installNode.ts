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
import { installSvNodeStandalone } from '@canton-network/splice-pulumi-common-sv/src/sv';

import { installParticipant } from './participant';

export async function installNode(sv: string, auth0Client: Auth0Client): Promise<void> {
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
  if (splitSvDeploymentEnabled && staticConfig.nodeName !== svRunbookConfig.nodeName) {
    await installSvNodeStandalone(xns, staticConfig, config, auth0Client);
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

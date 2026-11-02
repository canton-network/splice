// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  auth0UserNameEnvVarSource,
  CLUSTER_HOSTNAME,
  ExactNamespace,
  exactNamespace,
  imagePullSecret,
  installLedgerApiUserSecret,
  installSpliceHelmChart,
  type Auth0Client,
} from '@canton-network/splice-pulumi-common';
import {
  configForDSyncNode,
  dSyncMigrationConfigForNode,
  installLoopback,
  installParticipant,
} from '@canton-network/splice-pulumi-common-sv';

function installIngress(xns: ExactNamespace) {
  const dsyncMigrationConfig = dSyncMigrationConfigForNode(xns.logicalName);

  return installSpliceHelmChart(
    xns,
    'ingress-sv',
    'splice-cluster-ingress-runbook',
    {
      withSvIngress: true, // this actually installs too much, but doesn't seem worth having a separate config parameter to avoid that
      ingress: {
        decentralizedSynchronizer: {
          migrationIds: dsyncMigrationConfig.runningMigrations().map(x => x.id.toString()),
        },
      },
      spliceDomainNames: {},
      cluster: {
        hostname: CLUSTER_HOSTNAME,
        svNamespace: xns.logicalName,
        svIngressName: xns.logicalName,
      },
      rateLimit: {
        scan: {
          enable: false,
        },
      },
    },
    dsyncMigrationConfig.active.version,
    { dependsOn: [xns.ns] }
  );
}

export async function installNode(auth0Client: Auth0Client, namespace: string): Promise<void> {
  const xns = exactNamespace(namespace, false);
  const imagePullDeps = imagePullSecret(xns);
  installLoopback(xns);
  installIngress(xns);

  const config = configForDSyncNode(namespace);
  const dsyncMigrationConfig = dSyncMigrationConfigForNode(namespace);
  const ledgerApiUserSecret = await installLedgerApiUserSecret(auth0Client, xns, 'dsync', 'dsync');
  await installParticipant(
    {
      xns,
      participant: config.participant,
      logging: config.logging,
      auth0: auth0Client.getCfg(),
      version: dsyncMigrationConfig.active.version,
      migrationId: dsyncMigrationConfig.activeMigrationId,
      participantAdminUserNameFrom: auth0UserNameEnvVarSource('dsync', true),
    },
    { dependsOn: [...imagePullDeps, ledgerApiUserSecret] }
  );
}

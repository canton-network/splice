// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as postgres from '@canton-network/splice-pulumi-common/src/postgres';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  CLUSTER_HOSTNAME,
  CnInput,
  ExactNamespace,
  HELM_CHART_TIMEOUT_SEC,
  HELM_MAX_HISTORY_SIZE,
  activeVersion,
  appsKubernetesScheduling,
  installWalletGatewayAdminSecret,
  spliceConfig,
} from '@canton-network/splice-pulumi-common';
import { SplicePlaceholderResource } from '@canton-network/splice-pulumi-common/src/pulumiUtilResources';

import { WalletGatewayConfig } from './config';

export async function installWalletGateway(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  config: WalletGatewayConfig,
  // The gateway talks to the participant's ledger API on startup, so sequence it after the validator
  dependsOn: CnInput<pulumi.Resource>[] = [],
  defaultPostgres?: postgres.Postgres
): Promise<pulumi.Resource> {
  if (spliceConfig.pulumiProjectConfig.installDataOnly) {
    return new SplicePlaceholderResource('wallet-gateway');
  }
  const ns = xns.logicalName;
  const auth0Cfg = auth0Client.getCfg();
  const nsAuth0 = auth0Cfg.namespacedConfigs[ns];
  if (!nsAuth0?.uiClientIds.walletGateway) {
    throw new Error(`No wallet gateway Auth0 client configured for namespace ${ns}`);
  }

  const publicUrl = `https://walletgateway.${ns}.${CLUSTER_HOSTNAME}`;
  const portfolioUrl = `https://portfolio.${ns}.${CLUSTER_HOSTNAME}`;
  const scanUrl = config.scanUrl ?? `https://scan.sv-2.${CLUSTER_HOSTNAME}`;

  const adminSecret = await installWalletGatewayAdminSecret(auth0Client, xns);

  const wgPostgres =
    defaultPostgres ||
    (await postgres.installPostgres(
      xns,
      'wallet-gateway-pg',
      'wallet-gateway-pg',
      activeVersion,
      spliceConfig.pulumiProjectConfig.cloudSql,
      spliceConfig.pulumiProjectConfig.defaultSplicePostgresConfig,
      true
    ));

  const gateway = new k8s.helm.v3.Release(
    `${ns}-wallet-gateway`,
    {
      name: 'wallet-gateway',
      chart: 'oci://ghcr.io/digital-asset/wallet-gateway/helm/wallet-gateway',
      version: config.version,
      namespace: xns.ns.metadata.name,
      timeout: HELM_CHART_TIMEOUT_SEC,
      maxHistory: HELM_MAX_HISTORY_SIZE,
      values: {
        image: { tag: `v${config.version}` },
        ...appsKubernetesScheduling,
        oauthSecrets: {
          OAUTH2_ADMIN_CLIENT_SECRET: {
            secretRef: { name: 'wallet-gateway-admin-oauth', key: 'client-secret' },
          },
        },
        extraEnv: [
          {
            name: 'WG_STORE_PASSWORD',
            valueFrom: {
              secretKeyRef: { name: wgPostgres.secretName, key: 'postgresPassword' },
            },
          },
        ],
        signing: {}, // participant-based signing, no custody drivers
        config: {
          ...(config.logLevel ? { logging: { level: config.logLevel } } : {}),
          kernel: {
            id: `splice-${ns}`,
            clientType: 'remote',
            publicUrl,
          },
          server: {
            port: 3030,
            allowedOrigins: [portfolioUrl],
            dappPath: '/api/v0/dapp',
            userPath: '/api/v0/user',
            requestSizeLimit: '5mb',
            requestRateLimit: 10000,
            trustProxy: 1,
            signingWorker: { pollInterval: 5000 },
          },
          store: {
            connection: {
              type: 'postgres',
              host: wgPostgres.address,
              port: 5432,
              user: wgPostgres.userName,
              passwordEnv: 'WG_STORE_PASSWORD',
              database: 'wg_store',
            },
          },
          signingStore: {
            connection: {
              type: 'postgres',
              host: wgPostgres.address,
              port: 5432,
              user: wgPostgres.userName,
              passwordEnv: 'WG_STORE_PASSWORD',
              database: 'wg_signing_store',
            },
          },
          bootstrap: {
            idps: [
              {
                id: 'idp-auth0',
                type: 'oauth',
                issuer: `https://${auth0Cfg.auth0Domain}/`,
                configUrl: `https://${auth0Cfg.auth0Domain}/.well-known/openid-configuration`,
              },
            ],
            networks: [
              {
                id: `canton:${ns}`,
                name: `Splice ${ns}`,
                description: `Splice ${ns} wallet gateway network`,
                identityProviderId: 'idp-auth0',
                ledgerApi: { baseUrl: 'http://participant:7575' },
                auth: {
                  method: 'authorization_code',
                  audience: nsAuth0.audiences.ledgerApi,
                  scope: 'openid daml_ledger_api offline_access',
                  clientId: nsAuth0.uiClientIds.walletGateway,
                },
                adminAuth: {
                  method: 'client_credentials',
                  audience: nsAuth0.audiences.ledgerApi,
                  scope: 'daml_ledger_api',
                  clientId: nsAuth0.backendClientIds.validator,
                  clientSecretEnv: 'OAUTH2_ADMIN_CLIENT_SECRET',
                },
              },
            ],
          },
        },
      },
    },
    { dependsOn: dependsOn.concat([xns.ns, adminSecret, wgPostgres]) }
  );

  new k8s.helm.v3.Release(
    `${ns}-portfolio`,
    {
      name: 'portfolio',
      chart: 'oci://ghcr.io/digital-asset/splice-portfolio/helm/splice-portfolio',
      version: config.portfolioVersion,
      namespace: xns.ns.metadata.name,
      timeout: HELM_CHART_TIMEOUT_SEC,
      maxHistory: HELM_MAX_HISTORY_SIZE,
      values: {
        image: { tag: `v${config.portfolioVersion}` },
        ...appsKubernetesScheduling,
        config: {
          amulet: {
            validatorUrl: `https://wallet.${ns}.${CLUSTER_HOSTNAME}/api/validator`,
            registry: `${scanUrl}/registry/`,
          },
          token: {
            validatorUrl: `https://wallet.${ns}.${CLUSTER_HOSTNAME}/api/validator`,
            registries: [{ url: `${scanUrl}/registry/`, name: 'Amulet registry' }],
          },
        },
      },
    },
    { dependsOn: dependsOn.concat([xns.ns]) }
  );

  return gateway;
}

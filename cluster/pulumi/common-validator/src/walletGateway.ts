// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  CLUSTER_HOSTNAME,
  CnInput,
  ExactNamespace,
  appsKubernetesScheduling,
  installWalletGatewayAdminSecret,
} from '@canton-network/splice-pulumi-common';

import { WalletGatewayConfig } from './config';

export async function installWalletGateway(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  config: WalletGatewayConfig,
  // The gateway talks to the participant's ledger API on startup, so sequence it after the validator
  dependsOn: CnInput<pulumi.Resource>[] = []
): Promise<pulumi.Resource> {
  const ns = xns.logicalName;
  const auth0Cfg = auth0Client.getCfg();
  const nsAuth0 = auth0Cfg.namespacedConfigs[ns];
  if (!nsAuth0?.uiClientIds.walletGateway) {
    throw new Error(`No wallet gateway Auth0 client configured for namespace ${ns}`);
  }

  const publicUrl = `https://walletgateway.${ns}.${CLUSTER_HOSTNAME}`;
  const portfolioUrl = `https://portfolio.${ns}.${CLUSTER_HOSTNAME}`;
  // sv-1's scan is exposed under its `sv-2` ingress name (see svConfigsBasic.ts)
  const scanUrl = config.scanUrl ?? `https://scan.sv-2.${CLUSTER_HOSTNAME}`;

  const adminSecret = await installWalletGatewayAdminSecret(auth0Client, xns);

  const gateway = new k8s.helm.v3.Release(
    `${ns}-wallet-gateway`,
    {
      name: 'wallet-gateway',
      chart: 'oci://ghcr.io/digital-asset/wallet-gateway/helm/wallet-gateway',
      version: config.version,
      namespace: xns.ns.metadata.name,
      values: {
        image: { tag: `v${config.version}` },
        ...appsKubernetesScheduling,
        oauthSecrets: {
          OAUTH2_ADMIN_CLIENT_SECRET: {
            secretRef: { name: 'wallet-gateway-admin-oauth', key: 'client-secret' },
          },
        },
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
          store: { connection: { type: 'sqlite', database: '/tmp/store.sqlite' } },
          signingStore: { connection: { type: 'sqlite', database: '/tmp/signing_store.sqlite' } },
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
                  // `email` is required: the gateway resolves the user's email via OIDC userinfo
                  scope: 'openid email profile daml_ledger_api offline_access',
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
    { dependsOn: dependsOn.concat([xns.ns, adminSecret]) }
  );

  const portfolio = new k8s.helm.v3.Release(
    `${ns}-portfolio`,
    {
      name: 'portfolio',
      chart: 'oci://ghcr.io/digital-asset/splice-portfolio/helm/splice-portfolio',
      version: config.portfolioVersion,
      namespace: xns.ns.metadata.name,
      // The chart has no scheduling values, so the pod only becomes schedulable
      // after the DeploymentPatch below; waiting here would deadlock
      skipAwait: true,
      values: {
        image: { tag: `v${config.portfolioVersion}` },
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

  new k8s.apps.v1.DeploymentPatch(
    `${ns}-portfolio-scheduling`,
    {
      metadata: {
        name: 'portfolio-splice-portfolio',
        namespace: xns.ns.metadata.name,
        annotations: { 'pulumi.com/patchForce': 'true' },
      },
      spec: {
        template: {
          spec: { ...appsKubernetesScheduling },
        },
      },
    },
    { dependsOn: [portfolio] }
  );

  return gateway;
}

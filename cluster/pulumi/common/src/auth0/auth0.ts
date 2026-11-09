// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { KubeConfig, CoreV1Api } from '@kubernetes/client-node';
import { Output } from '@pulumi/pulumi';
import { AuthenticationClient, ManagementClient, withRetries } from 'auth0';

import { config, isMainNet } from '../config';
import { StackReferences } from '../stackReferences';
import { fixedTokens } from '../utils';
import type {
  Auth0Client,
  Auth0SecretMap,
  Auth0ClientSecret,
  Auth0Config,
  Auth0ClusterConfig,
  Auth0NamespaceConfig,
} from './auth0types';
import {
  FixedTokensCache,
  getOrUpdateToken,
  loadFixedTokesCache,
  persistFixedTokensCache,
} from './fixedTokensCache';

export class Auth0Fetch implements Auth0Client {
  private secrets: Auth0SecretMap | undefined;
  private fixedTokensCache: FixedTokensCache | undefined;

  private cfg: Auth0Config;

  constructor(cfg: Auth0Config) {
    this.cfg = cfg;
  }

  public getCfg(): Auth0Config {
    return this.cfg;
  }

  private async loadSecrets(): Promise<Auth0SecretMap> {
    const client = new ManagementClient({
      domain: this.cfg.auth0Domain,
      clientId: this.cfg.auth0MgtClientId,
      clientSecret: this.cfg.auth0MgtClientSecret,
    });
    // enable retries
    const reqOptions = {
      ...withRetries(10),
      timeoutInSeconds: 30,
    };

    const secrets = new Map() as Auth0SecretMap;
    let page = await client.clients.list({ per_page: 50 }, reqOptions);
    for (const client of page.data) {
      if (client.client_id && client.client_secret) {
        secrets.set(client.client_id, client as Auth0ClientSecret);
      }
    }
    while (page.hasNextPage()) {
      page = await page.getNextPage();
      for (const client of page.data) {
        if (client.client_id && client.client_secret) {
          secrets.set(client.client_id, client as Auth0ClientSecret);
        }
      }
    }
    return secrets;
  }

  private getK8sClient(): CoreV1Api {
    const kc = new KubeConfig();
    kc.loadFromDefault();
    return kc.makeApiClient(CoreV1Api);
  }

  // TODO: rename
  public async loadAuth0Cache(): Promise<void> {
    if (!fixedTokens()) {
      await pulumi.log.debug('Fixed tokens not enabled, skipping Auth0 cache load');
      this.fixedTokensCache = undefined;
      return;
    }

    this.fixedTokensCache = await loadFixedTokesCache(this.getK8sClient());
  }

  // TODO: rename
  public async saveAuth0Cache(): Promise<void> {
    if (this.fixedTokensCache === undefined) {
      await pulumi.log.debug('No auth0 cache to save');
      return;
    }

    await persistFixedTokensCache(this.getK8sClient(), this.fixedTokensCache);
  }

  public async getSecrets(): Promise<Auth0SecretMap> {
    if (this.secrets === undefined) {
      await pulumi.log.debug('Calling Auth0 API for getSecrets()');
      this.secrets = await this.loadSecrets();
    }
    return this.secrets;
  }

  // TODO: rename
  public async getClientAccessToken(
    clientId: string,
    clientSecret: string,
    audience: string,
    api: 'ledgerApi' | 'participantAdminApi'
  ): Promise<string> {
    if (this.fixedTokensCache === undefined) {
      throw new Error(); // TODO: only used for fixed tokens setup
    }
    return await getOrUpdateToken(this.fixedTokensCache, clientId, api, async () => {
      await pulumi.log.debug(
        `Requesting a new fixed [${api}] token (audience: [${audience}]) for [${clientId}] from Auth0.`
      );
      const auth0 = new AuthenticationClient({
        domain: this.cfg.auth0Domain,
        clientId: clientId,
        clientSecret: clientSecret,
      });

      const tokenResponse = await auth0.oauth.clientCredentialsGrant({
        audience,
      });

      return {
        accessToken: tokenResponse.data.access_token,
        expiry: new Date(Date.now() + tokenResponse.data.expires_in * 1000),
      };
    });
  }
}

export function auth0UserNameEnvVar(
  name: string,
  secretName: string | null = null
): k8s.types.input.core.v1.EnvVar {
  if (!secretName) {
    secretName = name;
  }
  return {
    name: `SPLICE_APP_${name.toUpperCase()}_LEDGER_API_AUTH_USER_NAME`,
    valueFrom: auth0UserNameEnvVarSource(secretName),
  };
}

export function auth0UserNameEnvVarSource(
  secretName: string,
  userOnlySecret: boolean = false
): k8s.types.input.core.v1.EnvVarSource {
  return {
    secretKeyRef: {
      key: 'ledger-api-user',
      name: `splice-app-${secretName.toLowerCase().replaceAll('_', '-')}-ledger-api-${userOnlySecret ? 'user' : 'auth'}`,
      optional: false,
    },
  };
}

export enum Auth0ClientType {
  RUNBOOK,
  MAINSTACK,
}

export function getAuth0ClusterConfig(): Output<Auth0ClusterConfig> {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const infraOutput: pulumi.Output<any> = StackReferences.infra.requireOutput('auth0');
  return infraOutput as pulumi.Output<Auth0ClusterConfig>;
}

export function getAuth0Config(clientType: Auth0ClientType): Output<Auth0Fetch> {
  const auth0ClusterCfg = getAuth0ClusterConfig();
  switch (clientType) {
    case Auth0ClientType.RUNBOOK:
      if (!auth0ClusterCfg.svRunbook) {
        throw new Error('missing sv runbook auth0 output');
      }
      return auth0ClusterCfg.svRunbook.apply(cfg => {
        if (!cfg) {
          throw new Error('missing sv runbook auth0 output');
        }
        cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET');
        return new Auth0Fetch(cfg);
      });
    case Auth0ClientType.MAINSTACK:
      if (isMainNet) {
        if (!auth0ClusterCfg.mainnet) {
          throw new Error('missing mainNet auth0 output');
        }
        return auth0ClusterCfg.mainnet.apply(cfg => {
          if (!cfg) {
            throw new Error('missing mainNet auth0 output');
          }
          cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_MAIN_MANAGEMENT_API_CLIENT_SECRET');
          return new Auth0Fetch(cfg);
        });
      } else {
        if (!auth0ClusterCfg.cantonNetwork) {
          throw new Error('missing cantonNetwork auth0 output');
        }
        return auth0ClusterCfg.cantonNetwork.apply(cfg => {
          if (!cfg) {
            throw new Error('missing cantonNetwork auth0 output');
          }
          cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET');
          return new Auth0Fetch(cfg);
        });
      }
  }
}

export function getNamespaceConfig(
  auth0Config: Auth0Config,
  namespace: string
): Auth0NamespaceConfig {
  const nsConfig = auth0Config.namespacedConfigs[namespace];
  if (!nsConfig) {
    throw new Error(`No Auth0 configuration for namespace ${namespace}`);
  }
  return nsConfig;
}

export function getClientId(
  auth0: Auth0Client | Auth0Config,
  clientName: 'sv' | 'validator' | 'splitwell',
  namespace: string
): string {
  const config = 'getCfg' in auth0 ? auth0.getCfg() : auth0;
  switch (clientName) {
    case 'sv':
      return getNamespaceConfig(config, namespace).backendClientIds.svApp!;
    case 'validator':
      return getNamespaceConfig(config, namespace).backendClientIds.validator;
    case 'splitwell':
      return getNamespaceConfig(config, namespace).backendClientIds.splitwell!;
  }
}

export const svUserIds = (auth0Cfg: Auth0Config): Output<string[]> => {
  const temp = gcp.secretmanager.getSecretVersionOutput({
    secret: `pulumi-user-configs-${auth0Cfg.auth0Domain.replace('.us.auth0.com', '')}`,
  });
  return temp.apply(config => {
    const secretData = config.secretData;
    const json = JSON.parse(secretData);
    const ret: string[] = [];
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    json.forEach((user: any) => {
      ret.push(user.user_id);
    });
    return ret;
  });
};

export const ansDomainPrefix = 'cns';

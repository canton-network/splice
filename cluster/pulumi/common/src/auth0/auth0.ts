// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { KubeConfig, CoreV1Api } from '@kubernetes/client-node';
import { Output } from '@pulumi/pulumi';
import { AuthenticationClient, ManagementClient, TokenSet, withRetries } from 'auth0';

import { config, isMainNet } from '../config';
import { StackReferences } from '../stackReferences';
import { fixedTokens } from '../utils';
import type {
  Auth0Client,
  Auth0SecretMap,
  Auth0ClientAccessToken,
  Auth0ClientSecret,
  Auth0Config,
  Auth0ClusterConfig,
  Auth0NamespaceConfig,
} from './auth0types';

type Auth0CacheMap = Record<string, Auth0ClientAccessToken>;

/* Access tokens deployed into a cluster need to have a lifetime at
 * least as long as the cluster is expected to run. This means that
 * cached tokens set to expire during the expected lifetime of an
 * environment need to be refreshed at deployment even if they aren't
 * quite expired.
 *
 * This constant sets the length of time tokens are expected to remain
 * valid. It is currently eight days, based on the seven day life of
 * TestNet and a short additional grace period.
 */
const REQUIRED_TOKEN_LIFETIME = 8 * 86400;

function addTimeSeconds(t: Date, seconds: number): Date {
  const t2 = new Date(t);
  t2.setSeconds(t2.getSeconds() + seconds);
  return t2;
}

export class Auth0Fetch implements Auth0Client {
  private secrets: Auth0SecretMap | undefined;
  private auth0Cache: Auth0CacheMap | undefined;
  private hasDiffsToSave = false;

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

  // Each Pulumi stack gets its own cache secret to avoid concurrent write races
  // when multiple stacks (e.g., validator1, splitwell) run in parallel.
  private get cacheSecretName(): string {
    return `${this.cfg.fixedTokenCacheName}-${pulumi.getProject()}-${pulumi.getStack()}`;
  }

  public async loadAuth0Cache(): Promise<void> {
    if (!fixedTokens()) {
      await pulumi.log.debug('Fixed tokens not enabled, skipping Auth0 cache load');
      this.auth0Cache = undefined;
      return;
    }

    await pulumi.log.debug('Loading Auth0 Cache');
    const cacheMap = {} as Auth0CacheMap;

    try {
      const k8sApi = this.getK8sClient();
      const cacheSecret = await k8sApi.readNamespacedSecret({
        name: this.cacheSecretName,
        namespace: 'default',
      });

      const data = cacheSecret.data;

      for (const clientId in data) {
        cacheMap[clientId] = JSON.parse(Buffer.from(data[clientId], 'base64').toString('ascii'));
      }

      await pulumi.log.debug('Auth0 cache loaded...');
    } catch (e) {
      await pulumi.log.warn('No Auth0 cache secret found');
    }

    this.auth0Cache = cacheMap;
  }

  public async saveAuth0Cache(): Promise<void> {
    const data = {} as Record<string, string>;
    await pulumi.log.debug('Saving Auth0 cache');

    if (!this.auth0Cache) {
      await pulumi.log.debug('No auth0 cache to save');
      return;
    }

    if (!this.hasDiffsToSave) {
      await pulumi.log.debug('No auth0 cache diffs to save');
      return;
    }

    for (const clientId in this.auth0Cache) {
      const cachedToken = this.auth0Cache[clientId];

      data[clientId] = Buffer.from(JSON.stringify(cachedToken)).toString('base64');
    }

    const secretBody = {
      apiVersion: 'v1',
      kind: 'Secret',
      metadata: {
        name: this.cacheSecretName,
      },
      data,
    };

    try {
      await pulumi.log.info('Attempting to create secret');
      const k8sApi = this.getK8sClient();
      await k8sApi.createNamespacedSecret({
        namespace: 'default',
        body: secretBody,
      });
    } catch (_) {
      try {
        await pulumi.log.info('Deleting existing secret');
        const k8sApi = this.getK8sClient();
        await k8sApi.deleteNamespacedSecret({
          name: this.cacheSecretName,
          namespace: 'default',
        });

        await pulumi.log.info('Creating new secret');
        await k8sApi.createNamespacedSecret({
          namespace: 'default',
          body: secretBody,
        });
      } catch (e) {
        await pulumi.log.error(`Auth0 cache update failed: ${JSON.stringify(e)}`);
        process.exit(1);
      }
    }

    await pulumi.log.debug('Auth0 cache saved');
  }

  public async getSecrets(): Promise<Auth0SecretMap> {
    if (this.secrets === undefined) {
      await pulumi.log.debug('Calling Auth0 API for getSecrets()');
      this.secrets = await this.loadSecrets();
    }
    return this.secrets;
  }

  public async getClientAccessToken(
    clientId: string,
    clientSecret: string,
    audience: string
  ): Promise<string> {
    await pulumi.log.debug('Getting access token for Auth0 client: ' + clientId);

    const now = new Date();

    if (this.auth0Cache) {
      const cachedSecret = this.auth0Cache[clientId];
      if (cachedSecret) {
        const cachedSecretExpiry = new Date(cachedSecret.expiry);
        if (addTimeSeconds(now, REQUIRED_TOKEN_LIFETIME) > cachedSecretExpiry) {
          await pulumi.log.info('Ignoring expired cached Auth0 token for client: ' + clientId);
        } else {
          await pulumi.log.debug('Using cached Auth0 token for client: ' + clientId);
          return cachedSecret.accessToken;
        }
      }
    }

    await pulumi.log.debug(
      'Querying access token for Auth0 client: ' + clientId + ' with audience ' + audience
    );
    const auth0 = new AuthenticationClient({
      domain: this.cfg.auth0Domain,
      clientId: clientId,
      clientSecret: clientSecret,
    });

    const tokenResponse = await auth0.oauth.clientCredentialsGrant({
      audience,
    });

    const { expires_in } = tokenResponse.data;

    if (expires_in < REQUIRED_TOKEN_LIFETIME) {
      /* If you see this error, you either need to decrease the required token
       * lifetime or extend the length of the tokens issued by Auth0
       * (configured in the configuration of the ledger-api API in auth0).
       */
      await pulumi.log.error(
        `Auth0 access token issued with expiry (${expires_in}) too short to meet REQUIRED_TOKEN_LIFETIME (${REQUIRED_TOKEN_LIFETIME})`
      );
      process.exit(1);
    }

    const expiry = addTimeSeconds(now, expires_in);

    await this.cacheNewToken(clientId, expiry, tokenResponse.data);

    return tokenResponse.data.access_token;
  }

  private async cacheNewToken(clientId: string, expiry: Date, tokenResponse: TokenSet) {
    await pulumi.log.debug(
      'Caching access token for Auth0 client: ' + clientId + ' expiry: ' + expiry.toJSON()
    );

    if (this.auth0Cache && tokenResponse.access_token) {
      this.hasDiffsToSave = true;
      this.auth0Cache[clientId] = {
        accessToken: tokenResponse.access_token,
        expiry: expiry.toJSON(),
      };
    }
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

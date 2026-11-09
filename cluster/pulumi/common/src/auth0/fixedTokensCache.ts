// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { CoreV1Api as K8sClientV1 } from '@kubernetes/client-node';
import _ from 'lodash';

import { Auth0ClientAccessToken } from './auth0types';

// TODO: tests
export type FixedTokensCache = {
  tokens: Record<ClientId, ClientTokens>;
  persisted: boolean;
};

type ClientId = string;

type ClientTokens = {
  ledgerApi?: Auth0ClientAccessToken;
  participantAdminApi?: Auth0ClientAccessToken;
};

export async function getOrUpdateToken(
  cache: FixedTokensCache,
  clientId: ClientId,
  api: 'ledgerApi' | 'participantAdminApi',
  renewToken: () => Promise<Auth0ClientAccessToken>
): Promise<string> {
  await pulumi.log.debug(`Getting the fixed [${api}] token for client [${clientId}].`);

  const cachedToken = cache.tokens[clientId]?.[api];
  if (cachedToken === undefined || requiresRenewal(cachedToken)) {
    await pulumi.log.info(
      `The cached fixed [${api}] token for client [${clientId}] requires renewal.`
    );
    const newToken = await renewToken();

    if (requiresRenewal(newToken)) {
      /* If you see this error, you either need to decrease the required token
       * lifetime or extend the length of the tokens issued by Auth0
       * (configured in the configuration of the ledger-api API in auth0).
       */
      const tokenValiditySeconds = (newToken.expiry.getTime() - Date.now()) / 1000;
      await pulumi.log.error(
        `Auth0 access token issued with expiry of [${tokenValiditySeconds}s], too short to meet the required token lifetime of [${REQUIRED_TOKEN_LIFETIME_SECONDS}s].`
      );
      process.exit(1);
    }

    await pulumi.log.debug(
      `Caching a new fixed [${api}] token for client [${clientId}] expiring at [${newToken.expiry.toJSON()}].`
    );

    cache.tokens[clientId] = {
      ...cache.tokens[clientId],
      [api]: newToken,
    };
    cache.persisted = false;

    return newToken.accessToken;
  } else {
    await pulumi.log.debug(`Using cached fixed [${api}] token for client [${clientId}].`);
    return cachedToken.accessToken;
  }
}

function requiresRenewal(token: Auth0ClientAccessToken): boolean {
  const now = new Date();
  return addTimeSeconds(now, REQUIRED_TOKEN_LIFETIME_SECONDS) > token.expiry;
}

function addTimeSeconds(t: Date, seconds: number): Date {
  const t2 = new Date(t);
  t2.setSeconds(t2.getSeconds() + seconds);
  return t2;
}

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
const REQUIRED_TOKEN_LIFETIME_SECONDS = 8 * 86400;

export async function loadFixedTokesCache(k8sClient: K8sClientV1): Promise<FixedTokensCache> {
  await pulumi.log.debug('Loading fixed tokens cache...');

  try {
    const cacheSecret = await k8sClient.readNamespacedSecret({
      name: getCacheSecretName(),
      namespace: 'default',
    });

    const { version, ...data } = cacheSecret.data ?? {};

    const parsedVersion =
      version !== undefined ? Number.parseInt(Buffer.from(version, 'base64').toString('ascii')) : 0;

    await pulumi.log.debug(`Detected fixed tokens cache format version [${parsedVersion}].`);

    let tokens: FixedTokensCache['tokens'];
    switch (parsedVersion) {
      case 0:
        tokens = parseFixedTokensCacheV0(data);
        break;
      case 1:
        tokens = parseFixedTokensCacheV1(data);
        break;
      default:
        throw new Error(); // TODO
    }

    await pulumi.log.debug('Fixed tokens cache loaded.');

    return {
      tokens,
      persisted: true,
    };
  } catch (e) {
    await pulumi.log.warn('No fixed tokens cache secret found.');

    return {
      tokens: {},
      persisted: false,
    };
  }
}

function parseFixedTokensCacheV0(data: Record<string, string>): FixedTokensCache['tokens'] {
  return _.mapValues(data, value => ({
    ledgerApi: JSON.parse(Buffer.from(value, 'base64').toString('ascii'), parseExpiryDates),
  }));
}

function parseFixedTokensCacheV1(data: Record<string, string>): FixedTokensCache['tokens'] {
  return _.mapValues(data, value =>
    JSON.parse(Buffer.from(value, 'base64').toString('ascii'), parseExpiryDates)
  );
}

function parseExpiryDates(key: string, value: unknown): unknown {
  if (key === 'expiry' && typeof value === 'string') {
    return new Date(value);
  } else {
    return value;
  }
}

export async function persistFixedTokensCache(
  k8sClient: K8sClientV1,
  cache: FixedTokensCache
): Promise<void> {
  if (cache.persisted) {
    await pulumi.log.debug('Current state of the fixed tokens cache is already persisted.');
    return;
  }

  await pulumi.log.debug('Persisting fixed tokens cache...');

  const data = _.mapValues(cache.tokens, clientTokens =>
    Buffer.from(JSON.stringify(clientTokens)).toString('base64')
  );

  const secretBody = {
    apiVersion: 'v1',
    kind: 'Secret',
    metadata: {
      name: getCacheSecretName(),
    },
    data: {
      ...data,
      version: Buffer.from('1').toString('base64'),
    },
  };

  try {
    await pulumi.log.info('Attempting to create a fixed tokens cache secret...');
    await k8sClient.createNamespacedSecret({
      namespace: 'default',
      body: secretBody,
    });
  } catch (_) {
    try {
      await pulumi.log.info('Deleting an existing fixed tokens cache secret...');
      await k8sClient.deleteNamespacedSecret({
        name: getCacheSecretName(),
        namespace: 'default',
      });

      await pulumi.log.info('Creating a new fixed tokens cache secret...');
      await k8sClient.createNamespacedSecret({
        namespace: 'default',
        body: secretBody,
      });
    } catch (e) {
      await pulumi.log.error(`Persisting fixed tokens cache failed: ${JSON.stringify(e)}`);
      process.exit(1);
    }
  }

  await pulumi.log.debug('Fixed tokens cache persisted successfully.');
}

// Each Pulumi stack gets its own cache secret to avoid concurrent write races
// when multiple stacks (e.g., validator1, splitwell) run in parallel.
function getCacheSecretName(): string {
  return `auth0-fixed-token-cache-${pulumi.getProject()}-${pulumi.getStack()}`;
}

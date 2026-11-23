// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { expect, jest, test } from '@jest/globals';
import {
  CoreV1Api as K8sClientV1,
  CoreV1ApiReadNamespacedSecretRequest,
  ApiException,
} from '@kubernetes/client-node';
import _ from 'lodash';

import { Auth0ClientAccessToken } from './auth0types';
import {
  encodeSecretValue,
  FixedTokensCache,
  getCacheSecretName,
  getOrUpdateToken,
  loadFixedTokesCache,
  persistFixedTokensCache,
  REQUIRED_TOKEN_LIFETIME_SECONDS,
} from './fixedTokensCache';

test('loading the fixed tokens cache when the underlying secret does not exist', async () => {
  const k8sClientMock = {
    readNamespacedSecret(request: CoreV1ApiReadNamespacedSecretRequest) {
      throw new ApiException(404, 'secret does not exist', undefined, {});
    },
  } as unknown as K8sClientV1;
  jest.spyOn(k8sClientMock, 'readNamespacedSecret');
  const expectedCacheState: FixedTokensCache = {
    tokens: {},
    persisted: false,
  };
  expect(await loadFixedTokesCache(k8sClientMock)).toEqual(expectedCacheState);
  expect(k8sClientMock.readNamespacedSecret).toHaveBeenCalledTimes(1);
});

test('loading the fixed tokens cache from the V0 format', async () => {
  const now = new Date();
  const fiveMinutesFromNow = new Date(Date.now() + 1000 * 60 * 5);
  const k8sClientMock = {
    readNamespacedSecret(request: CoreV1ApiReadNamespacedSecretRequest) {
      return {
        data: encodeSecretData({
          client_a: { accessToken: 'token_123', expiry: now },
          client_b: { accessToken: 'token_456', expiry: fiveMinutesFromNow },
          // no version field
        }),
      };
    },
  } as unknown as K8sClientV1;
  jest.spyOn(k8sClientMock, 'readNamespacedSecret');
  const expectedCacheState: FixedTokensCache = {
    tokens: {
      client_a: {
        ledgerApi: { accessToken: 'token_123', expiry: now },
      },
      client_b: {
        ledgerApi: { accessToken: 'token_456', expiry: fiveMinutesFromNow },
      },
    },
    persisted: true,
  };
  expect(await loadFixedTokesCache(k8sClientMock)).toEqual(expectedCacheState);
  expect(k8sClientMock.readNamespacedSecret).toHaveBeenCalledTimes(1);
});

test('loading the fixed tokens cache from the V1 format', async () => {
  const now = new Date();
  const fiveMinutesFromNow = new Date(Date.now() + 1000 * 60 * 5);
  const k8sClientMock = {
    readNamespacedSecret(request: CoreV1ApiReadNamespacedSecretRequest) {
      return {
        data: encodeSecretData({
          client_a: {
            ledgerApi: { accessToken: 'token_123', expiry: now },
          },
          client_b: {
            participantAdminApi: { accessToken: 'token_456', expiry: fiveMinutesFromNow },
          },
          version: 1,
        }),
      };
    },
  } as unknown as K8sClientV1;
  jest.spyOn(k8sClientMock, 'readNamespacedSecret');
  const expectedCacheState: FixedTokensCache = {
    tokens: {
      client_a: {
        ledgerApi: { accessToken: 'token_123', expiry: now },
      },
      client_b: {
        participantAdminApi: { accessToken: 'token_456', expiry: fiveMinutesFromNow },
      },
    },
    persisted: true,
  };
  expect(await loadFixedTokesCache(k8sClientMock)).toEqual(expectedCacheState);
  expect(k8sClientMock.readNamespacedSecret).toHaveBeenCalledTimes(1);
});

test('loading the fixed tokens cache from an unknown format version', async () => {
  const k8sClientMock = {
    readNamespacedSecret(request: CoreV1ApiReadNamespacedSecretRequest) {
      return {
        data: encodeSecretData({
          version: 999,
        }),
      };
    },
  } as unknown as K8sClientV1;
  jest.spyOn(k8sClientMock, 'readNamespacedSecret');
  await expect(loadFixedTokesCache(k8sClientMock)).rejects.toThrow(
    'Unknown fixed tokens cache format version'
  );
  expect(k8sClientMock.readNamespacedSecret).toHaveBeenCalledTimes(1);
});

test('getting a token that is not cached', async () => {
  const cache: FixedTokensCache = {
    tokens: {},
    persisted: true,
  };
  const clientId = 'not-cached';
  const expiry = new Date(Date.now() + 1000 * REQUIRED_TOKEN_LIFETIME_SECONDS * 2);
  const tokenProvider = jest.fn(async () => ({
    accessToken: 'fresh-token',
    expiry,
  }));
  expect(await getOrUpdateToken(cache, clientId, 'ledgerApi', tokenProvider)).toEqual(
    'fresh-token'
  );
  expect(await getOrUpdateToken(cache, clientId, 'participantAdminApi', tokenProvider)).toEqual(
    'fresh-token'
  );
  expect(tokenProvider).toHaveBeenCalledTimes(2);
  expect(cache).toEqual({
    tokens: {
      [clientId]: {
        ledgerApi: { accessToken: 'fresh-token', expiry },
        participantAdminApi: { accessToken: 'fresh-token', expiry },
      },
    },
    persisted: false,
  });
});

test('getting a token that is already cached and does not require renewal', async () => {
  const clientId = 'cached';
  const cache: FixedTokensCache = {
    tokens: {
      [clientId]: {
        ledgerApi: {
          accessToken: 'cached-token',
          expiry: new Date(Date.now() + 1000 * REQUIRED_TOKEN_LIFETIME_SECONDS * 2),
        },
        participantAdminApi: {
          accessToken: 'cached-token',
          expiry: new Date(Date.now() + 1000 * REQUIRED_TOKEN_LIFETIME_SECONDS * 2),
        },
      },
    },
    persisted: true,
  };
  const cacheSnapshot = JSON.stringify(cache);
  const tokenProvider = jest.fn() as () => Promise<Auth0ClientAccessToken>;
  expect(await getOrUpdateToken(cache, clientId, 'ledgerApi', tokenProvider)).toEqual(
    'cached-token'
  );
  expect(await getOrUpdateToken(cache, clientId, 'participantAdminApi', tokenProvider)).toEqual(
    'cached-token'
  );
  expect(tokenProvider).not.toHaveBeenCalled();
  expect(JSON.stringify(cache)).toEqual(cacheSnapshot); // cache state did not change
});

test('getting a token that is already cached but requires renewal', async () => {
  const clientId = 'cached';
  const cache: FixedTokensCache = {
    tokens: {
      [clientId]: {
        ledgerApi: {
          accessToken: 'cached-token',
          expiry: new Date(Date.now() + 1000 * REQUIRED_TOKEN_LIFETIME_SECONDS * 0.5),
        },
        participantAdminApi: {
          accessToken: 'cached-token',
          expiry: new Date(Date.now() + 1000 * REQUIRED_TOKEN_LIFETIME_SECONDS * 0.5),
        },
      },
    },
    persisted: true,
  };
  const expiry = new Date(Date.now() + 1000 * REQUIRED_TOKEN_LIFETIME_SECONDS * 2);
  const tokenProvider = jest.fn(async () => ({
    accessToken: 'fresh-token',
    expiry,
  }));
  expect(await getOrUpdateToken(cache, clientId, 'ledgerApi', tokenProvider)).toEqual(
    'fresh-token'
  );
  expect(await getOrUpdateToken(cache, clientId, 'participantAdminApi', tokenProvider)).toEqual(
    'fresh-token'
  );
  expect(tokenProvider).toHaveBeenCalledTimes(2);
  expect(cache).toEqual({
    tokens: {
      [clientId]: {
        ledgerApi: { accessToken: 'fresh-token', expiry },
        participantAdminApi: { accessToken: 'fresh-token', expiry },
      },
    },
    persisted: false,
  });
});

test('getting a fresh token with an insufficient lifetime', async () => {
  const cache: FixedTokensCache = {
    tokens: {},
    persisted: true,
  };
  const cacheSnapshot = JSON.stringify(cache);
  const clientId = 'not-cached';
  const tokenProvider = jest.fn(async () => ({
    accessToken: 'fresh-token',
    expiry: new Date(Date.now() + 1000 * REQUIRED_TOKEN_LIFETIME_SECONDS * 0.5),
  }));
  await expect(getOrUpdateToken(cache, clientId, 'ledgerApi', tokenProvider)).rejects.toThrow(
    'too short to meet the required token lifetime'
  );
  await expect(
    getOrUpdateToken(cache, clientId, 'participantAdminApi', tokenProvider)
  ).rejects.toThrow('too short to meet the required token lifetime');
  expect(tokenProvider).toHaveBeenCalledTimes(2);
  expect(JSON.stringify(cache)).toEqual(cacheSnapshot); // cache state did not change
});

test('persisting the fixed tokens cache without changes to persist', async () => {
  const cache: FixedTokensCache = {
    tokens: {},
    persisted: true,
  };
  const cacheSnapshot = JSON.stringify(cache);
  const k8sClientMock = {
    createNamespacedSecret: jest.fn(),
  } as unknown as K8sClientV1;
  await expect(persistFixedTokensCache(k8sClientMock, cache)).resolves.toBeUndefined();
  expect(k8sClientMock.createNamespacedSecret).not.toHaveBeenCalled();
  expect(JSON.stringify(cache)).toEqual(cacheSnapshot); // cache state did not change
});

test('persisting the fixed tokens cache', async () => {
  const clientId = 'cached';
  const expiry = new Date(Date.now() + 1000 * REQUIRED_TOKEN_LIFETIME_SECONDS * 2);
  const cache: FixedTokensCache = {
    tokens: {
      [clientId]: {
        ledgerApi: { accessToken: 'cached-token', expiry },
        participantAdminApi: { accessToken: 'cached-token', expiry },
      },
    },
    persisted: false,
  };
  const k8sClientMock = {
    createNamespacedSecret: jest.fn(),
  } as unknown as K8sClientV1;
  jest.spyOn(k8sClientMock, 'createNamespacedSecret');
  await expect(persistFixedTokensCache(k8sClientMock, cache)).resolves.toBeUndefined();
  expect(k8sClientMock.createNamespacedSecret).toHaveBeenCalledTimes(1);
  expect(k8sClientMock.createNamespacedSecret).toHaveBeenCalledWith({
    namespace: 'default',
    body: {
      apiVersion: 'v1',
      kind: 'Secret',
      metadata: {
        name: getCacheSecretName(),
      },
      data: encodeSecretData({
        ...cache.tokens,
        version: 1,
      }),
    },
  });
  expect(cache.persisted).toEqual(true);
});

test('persisting the fixed tokens cache with a persisted previous version', async () => {
  const clientId = 'cached';
  const expiry = new Date(Date.now() + 1000 * REQUIRED_TOKEN_LIFETIME_SECONDS * 2);
  const cache: FixedTokensCache = {
    tokens: {
      [clientId]: {
        ledgerApi: { accessToken: 'cached-token', expiry },
        participantAdminApi: { accessToken: 'cached-token', expiry },
      },
    },
    persisted: false,
  };
  let secretExists = true;
  const k8sClientMock = {
    createNamespacedSecret() {
      if (secretExists) {
        throw new ApiException(409, 'secret already exists', undefined, {});
      } else {
        secretExists = true;
      }
    },
    deleteNamespacedSecret() {
      secretExists = false;
    },
  } as unknown as K8sClientV1;
  jest.spyOn(k8sClientMock, 'createNamespacedSecret');
  jest.spyOn(k8sClientMock, 'deleteNamespacedSecret');
  const createRequest = {
    namespace: 'default',
    body: {
      apiVersion: 'v1',
      kind: 'Secret',
      metadata: {
        name: getCacheSecretName(),
      },
      data: encodeSecretData({
        ...cache.tokens,
        version: 1,
      }),
    },
  };
  await expect(persistFixedTokensCache(k8sClientMock, cache)).resolves.toBeUndefined();
  expect(k8sClientMock.createNamespacedSecret).toHaveBeenCalledTimes(2);
  expect(k8sClientMock.deleteNamespacedSecret).toHaveBeenCalledTimes(1);
  expect(k8sClientMock.createNamespacedSecret).toHaveBeenNthCalledWith(1, createRequest);
  expect(k8sClientMock.deleteNamespacedSecret).toHaveBeenCalledWith({
    name: getCacheSecretName(),
    namespace: 'default',
  });
  expect(k8sClientMock.createNamespacedSecret).toHaveBeenNthCalledWith(2, createRequest);
  expect(cache.persisted).toEqual(true);
});

function encodeSecretData(data: Record<string, unknown>): Record<string, string> {
  return _.mapValues(data, encodeSecretValue);
}

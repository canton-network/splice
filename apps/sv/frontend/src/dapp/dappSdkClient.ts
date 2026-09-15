// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import type {
  AccountsChangedEvent,
  ConnectResult,
  DappSDK,
  LedgerApiParams,
  LedgerApiResult,
  ListAccountsResult,
  PrepareExecuteAndWaitResult,
  PrepareExecuteParams,
} from '@canton-network/dapp-sdk';

/**
 * Narrow facade over `@canton-network/dapp-sdk` (CIP-103) used by dApp mode.
 * All CIP-103 RPC interaction goes through this module so tests can mock a
 * single seam.
 *
 * The SDK is loaded lazily via a dynamic import: standard mode must never pull
 * wallet code (and its transitive Lit dependency) into the page — Lit logs a
 * dev-mode console warning that trips the strict console assertions in the
 * frontend integration tests, and the SDK is dead weight without a wallet.
 */
export interface DappSdkClient {
  readonly cip103RpcUrl: string;
  init(): Promise<void>;
  connect(): Promise<ConnectResult>;
  disconnect(): Promise<void>;
  isConnected(): Promise<ConnectResult>;
  listAccounts(): Promise<ListAccountsResult>;
  onAccountsChanged(listener: (accounts: AccountsChangedEvent) => void): Promise<void>;
  removeOnAccountsChanged(listener: (accounts: AccountsChangedEvent) => void): Promise<void>;
  prepareExecuteAndWait(params: PrepareExecuteParams): Promise<PrepareExecuteAndWaitResult>;
  ledgerApi(params: LedgerApiParams): Promise<LedgerApiResult>;
}

const createClient = (cip103RpcUrl: string): DappSdkClient => {
  let sdkPromise: Promise<DappSDK> | undefined;

  const getSdk = (): Promise<DappSDK> => {
    if (!sdkPromise) {
      sdkPromise = (async () => {
        const { DappSDK, RemoteAdapter } = await import('@canton-network/dapp-sdk');
        const sdk = new DappSDK();
        await sdk.init({
          additionalAdapters: [
            new RemoteAdapter({
              rpcUrl: cip103RpcUrl,
              name: 'CIP-103 RPC',
              description: 'Configured via splice_config dappMode.cip103RpcUrl',
            }),
          ],
        });
        return sdk;
      })();
    }
    return sdkPromise;
  };

  const requireSdk = (): Promise<DappSDK> => {
    if (!sdkPromise) {
      throw new Error('CIP-103 SDK is not loaded');
    }
    return sdkPromise;
  };

  return {
    cip103RpcUrl,
    async init(): Promise<void> {
      await getSdk();
    },
    async connect(): Promise<ConnectResult> {
      return (await getSdk()).connect();
    },
    async disconnect(): Promise<void> {
      await (await requireSdk()).disconnect();
    },
    async isConnected(): Promise<ConnectResult> {
      return (await getSdk()).isConnected();
    },
    async listAccounts(): Promise<ListAccountsResult> {
      return (await getSdk()).listAccounts();
    },
    async onAccountsChanged(listener: (accounts: AccountsChangedEvent) => void): Promise<void> {
      (await getSdk()).onAccountsChanged(listener);
    },
    async removeOnAccountsChanged(
      listener: (accounts: AccountsChangedEvent) => void
    ): Promise<void> {
      (await requireSdk()).removeOnAccountsChanged(listener);
    },
    async prepareExecuteAndWait(
      params: PrepareExecuteParams
    ): Promise<PrepareExecuteAndWaitResult> {
      return (await getSdk()).prepareExecuteAndWait(params);
    },
    async ledgerApi(params: LedgerApiParams): Promise<LedgerApiResult> {
      return (await getSdk()).ledgerApi(params);
    },
  };
};

let singleton: DappSdkClient | undefined;

/** App-wide client for the configured CIP-103 RPC endpoint (one per page load). */
export function getDappSdkClient(cip103RpcUrl: string): DappSdkClient {
  if (!singleton || singleton.cip103RpcUrl !== cip103RpcUrl) {
    singleton = createClient(cip103RpcUrl);
  }
  return singleton;
}

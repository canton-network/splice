// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { vi } from 'vitest';

import { config as baseConfig } from '../setup/config';

export const dappScanUrl = 'http://scan.localhost:4000/api/scan';
export const dappCip103RpcUrl = 'http://localhost:3030/api/v0/dapp';
export const dappSvPartyId =
  'Digital-Asset-2::1220ed548efbcc22bb5097bd5a98303d1d64ab519f9568cdc1676ef1630da1fa6832';
export const dappVoterPartyId = 'delegated-voter::1220aa00bb11cc22dd33ee44ff55';
export const dappVoteDelegationCid =
  '00votedelegation0000000000000000000000000000000000000000000000';

export const dappModeBlock = {
  enabled: true,
  scanUrl: dappScanUrl,
  cip103RpcUrl: dappCip103RpcUrl,
};

/** window.splice_config shape for dApp-mode tests: base test config + enabled dappMode block. */
export const dappConfig = {
  ...baseConfig,
  dappMode: dappModeBlock,
};

// The common config reader captures window.splice_config at module import time
// (see common/frontend/src/config/reader.ts), so tests must mutate the captured
// object rather than reassign the global.
export function enableDappModeConfig(overrides: Partial<typeof dappModeBlock> = {}): void {
  (window.splice_config as unknown as { dappMode?: unknown }).dappMode = {
    ...dappModeBlock,
    ...overrides,
  };
}

export function disableDappModeConfig(): void {
  delete (window.splice_config as unknown as { dappMode?: unknown }).dappMode;
}

/** Mock CIP-103 ledgerApi responses for a successful VoteDelegation ACS discovery. */
export function mockVoteDelegationLedgerApi(
  client: { ledgerApi: ReturnType<typeof vi.fn> },
  args: {
    voterPartyId?: string;
    svPartyId?: string;
    voteDelegationCid?: string;
  } = {}
): void {
  const voterPartyId = args.voterPartyId ?? dappVoterPartyId;
  const svPartyId = args.svPartyId ?? dappSvPartyId;
  const voteDelegationCid = args.voteDelegationCid ?? dappVoteDelegationCid;

  client.ledgerApi.mockImplementation(async (params: { resource: string }) => {
    if (params.resource === '/v2/state/ledger-end') {
      return { offset: 42 };
    }
    if (params.resource === '/v2/state/active-contracts') {
      return [
        {
          contractEntry: {
            JsActiveContract: {
              createdEvent: {
                contractId: voteDelegationCid,
                templateId: '#splice-dso-governance:Splice.DsoRules.VoteDelegation:VoteDelegation',
                createArgument: {
                  dso: 'DSO::1220',
                  sv: svPartyId,
                  voterParty: voterPartyId,
                },
              },
            },
          },
        },
      ];
    }
    throw new Error(`unexpected ledgerApi resource: ${params.resource}`);
  });
}

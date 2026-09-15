// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { dsoInfo } from '@canton-network/splice-common-test-handlers';
import * as scanOpenapi from '@canton-network/scan-openapi';
import BigNumber from 'bignumber.js';
import { describe, expect, test, vi } from 'vitest';

import { createDappSvAdminClient, DappModeUnsupportedError } from '../../dapp/dappSvAdminClient';
import { DappModeConfig } from '../../utils/config';

const dappMode: DappModeConfig = {
  scanUrl: 'http://scan.localhost:4000/api/scan',
  cip103RpcUrl: 'http://localhost:3030/api/v0/dapp',
};

const ACTIVE_SYNCHRONIZER_ID =
  'global-domain::1220d57d4ce92ad14bb5647b453f2ba69c721e69810ca7d376d2c1455323a6763c37';

const buildClient = (scanClient: scanOpenapi.ScanApi) =>
  createDappSvAdminClient({
    scanClient,
    dappMode,
    isWalletConnected: true,
    submitCastVote: vi.fn(async () => undefined),
    submitCreateVoteRequest: vi.fn(async () => undefined),
  });

describe('createDappSvAdminClient', () => {
  test('SV-node-only methods reject with DappModeUnsupportedError', async () => {
    const client = buildClient({} as scanOpenapi.ScanApi);

    await expect(client.prepareValidatorOnboarding(3600, 'hint')).rejects.toBeInstanceOf(
      DappModeUnsupportedError
    );
    await expect(client.updateDesiredAmuletPrice(new BigNumber(1))).rejects.toMatchObject({
      name: 'DappModeUnsupportedError',
      message: 'updateDesiredAmuletPrice is not available in dApp mode',
    });
    await expect(client.getCometBftNodeDebug()).rejects.toBeInstanceOf(DappModeUnsupportedError);
  });

  test('getPartyToParticipant decodes DsoRules and queries Scan with the active synchronizer', async () => {
    const getPartyToParticipantV1 = vi.fn(async () => ({
      participant_ids: ['participant::1220aa'],
    }));
    const scanClient = {
      getDsoInfo: vi.fn(async () => dsoInfo),
      getPartyToParticipantV1,
    } as unknown as scanOpenapi.ScanApi;

    const client = buildClient(scanClient);
    const response = await client.getPartyToParticipant('party::1220bb');

    expect(getPartyToParticipantV1).toHaveBeenCalledWith(ACTIVE_SYNCHRONIZER_ID, 'party::1220bb');
    expect(response).toEqual({ participant_ids: ['participant::1220aa'] });
  });

  test('getPartyToParticipant fails when DsoRules has no active synchronizer id', async () => {
    const dsoInfoWithoutSynchronizer = structuredClone(dsoInfo);
    (
      dsoInfoWithoutSynchronizer.dso_rules.contract.payload as {
        config: { decentralizedSynchronizer: { activeSynchronizerId: string } };
      }
    ).config.decentralizedSynchronizer.activeSynchronizerId = '';

    const scanClient = {
      getDsoInfo: vi.fn(async () => dsoInfoWithoutSynchronizer),
      getPartyToParticipantV1: vi.fn(),
    } as unknown as scanOpenapi.ScanApi;

    const client = buildClient(scanClient);
    await expect(client.getPartyToParticipant('party::1220bb')).rejects.toThrow(
      'DsoRules config does not expose an active synchronizer id'
    );
    expect(scanClient.getPartyToParticipantV1).not.toHaveBeenCalled();
  });
});

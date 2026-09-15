// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, test, vi } from 'vitest';

import { DappSdkClient } from '../../dapp/dappSdkClient';
import {
  discoverVoteDelegation,
  VoteDelegationDiscoveryError,
} from '../../dapp/discoverVoteDelegation';
import { dappSvPartyId, dappVoteDelegationCid, dappVoterPartyId } from './dappConfig';

const PACKAGE_NAME = 'splice-dso-governance';

const acsEntry = (args: { contractId: string; sv: string; voterParty: string }) => ({
  contractEntry: {
    JsActiveContract: {
      createdEvent: {
        contractId: args.contractId,
        templateId: `#${PACKAGE_NAME}:Splice.DsoRules.VoteDelegation:VoteDelegation`,
        createArgument: {
          dso: 'DSO::1220',
          sv: args.sv,
          voterParty: args.voterParty,
        },
      },
    },
  },
});

const buildClient = (acs: unknown[]): DappSdkClient => {
  const ledgerApi = vi.fn(async (params: { resource: string }) => {
    if (params.resource === '/v2/state/ledger-end') {
      return { offset: 7 };
    }
    if (params.resource === '/v2/state/active-contracts') {
      return acs;
    }
    throw new Error(`unexpected resource ${params.resource}`);
  });
  return { ledgerApi } as unknown as DappSdkClient;
};

describe('discoverVoteDelegation', () => {
  test('returns the single matching VoteDelegation', async () => {
    const client = buildClient([
      acsEntry({
        contractId: dappVoteDelegationCid,
        sv: dappSvPartyId,
        voterParty: dappVoterPartyId,
      }),
    ]);

    await expect(
      discoverVoteDelegation({
        sdkClient: client,
        voterPartyId: dappVoterPartyId,
      })
    ).resolves.toEqual({
      voteDelegationCid: dappVoteDelegationCid,
      svPartyId: dappSvPartyId,
      voterPartyId: dappVoterPartyId,
    });
  });

  test('errors when no VoteDelegation is visible', async () => {
    const client = buildClient([]);
    await expect(
      discoverVoteDelegation({
        sdkClient: client,
        voterPartyId: dappVoterPartyId,
      })
    ).rejects.toMatchObject({
      name: 'VoteDelegationDiscoveryError',
      code: 'none',
    } satisfies Partial<VoteDelegationDiscoveryError>);
  });

  test('errors when multiple VoteDelegation contracts match', async () => {
    const client = buildClient([
      acsEntry({
        contractId: '00delegation-a',
        sv: 'SV-A::1220',
        voterParty: dappVoterPartyId,
      }),
      acsEntry({
        contractId: '00delegation-b',
        sv: 'SV-B::1220',
        voterParty: dappVoterPartyId,
      }),
    ]);

    await expect(
      discoverVoteDelegation({
        sdkClient: client,
        voterPartyId: dappVoterPartyId,
      })
    ).rejects.toMatchObject({
      name: 'VoteDelegationDiscoveryError',
      code: 'ambiguous',
    } satisfies Partial<VoteDelegationDiscoveryError>);
  });

  test('errors when an ACS entry has no active created event', async () => {
    const client = buildClient([{ contractEntry: { JsEmpty: {} } }]);
    await expect(
      discoverVoteDelegation({
        sdkClient: client,
        voterPartyId: dappVoterPartyId,
      })
    ).rejects.toMatchObject({
      name: 'VoteDelegationDiscoveryError',
      code: 'ledger',
    } satisfies Partial<VoteDelegationDiscoveryError>);
  });

  test('ignores contracts for a different voterParty', async () => {
    const client = buildClient([
      acsEntry({
        contractId: '00other',
        sv: dappSvPartyId,
        voterParty: 'someone-else::1220',
      }),
    ]);

    await expect(
      discoverVoteDelegation({
        sdkClient: client,
        voterPartyId: dappVoterPartyId,
      })
    ).rejects.toMatchObject({ code: 'none' });
  });
});

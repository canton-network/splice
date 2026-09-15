// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { IdentifierFilter } from '@canton-network/canton-json-api-v2-openapi';
import { VoteDelegation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/VoteDelegation';

import { DappSdkClient } from './dappSdkClient';
import { createLedgerJsonApi } from './ledgerJsonApi';
import { getVoteDelegationTemplateId } from './voteDelegationCommands';

export type VoteDelegationDiscoveryFailureCode = 'none' | 'ambiguous' | 'ledger';

/** Raised when ACS discovery cannot resolve exactly one VoteDelegation. */
export class VoteDelegationDiscoveryError extends Error {
  readonly code: VoteDelegationDiscoveryFailureCode;

  constructor(message: string, code: VoteDelegationDiscoveryFailureCode) {
    super(message);
    this.name = 'VoteDelegationDiscoveryError';
    this.code = code;
  }
}

export interface DiscoveredVoteDelegation {
  readonly voteDelegationCid: string;
  readonly svPartyId: string;
  readonly voterPartyId: string;
}

/**
 * Query the connected wallet party's ACS for VoteDelegation contracts and
 * require exactly one match. The delegating SV party and contract id are taken
 * from that contract — they are not configured statically.
 */
export async function discoverVoteDelegation(args: {
  sdkClient: DappSdkClient;
  voterPartyId: string;
}): Promise<DiscoveredVoteDelegation> {
  const { sdkClient, voterPartyId } = args;
  const templateId = getVoteDelegationTemplateId();
  const ledger = createLedgerJsonApi(sdkClient);

  let activeAtOffset: number | undefined;
  try {
    ({ offset: activeAtOffset } = await ledger.getV2StateLedgerEnd());
  } catch (error) {
    throw new VoteDelegationDiscoveryError(
      `Failed to read ledger end while discovering VoteDelegation: ${
        error instanceof Error ? error.message : String(error)
      }`,
      'ledger'
    );
  }

  if (activeAtOffset === undefined) {
    throw new VoteDelegationDiscoveryError(
      'Ledger end response did not include a usable offset for VoteDelegation ACS discovery.',
      'ledger'
    );
  }

  let acsResult;
  try {
    acsResult = await ledger.postV2StateActiveContracts({
      activeAtOffset,
      eventFormat: {
        verbose: false,
        filtersByParty: {
          [voterPartyId]: {
            cumulative: [
              {
                identifierFilter: {
                  TemplateFilter: {
                    value: {
                      templateId,
                      includeCreatedEventBlob: false,
                    },
                  },
                } as IdentifierFilter,
              },
            ],
          },
        },
      },
    });
  } catch (error) {
    throw new VoteDelegationDiscoveryError(
      `Failed to query VoteDelegation ACS: ${
        error instanceof Error ? error.message : String(error)
      }`,
      'ledger'
    );
  }

  const matches = acsResult.flatMap(response => {
    const createdEvent = response.contractEntry?.JsActiveContract?.createdEvent;
    if (createdEvent === undefined) {
      throw new VoteDelegationDiscoveryError(
        'VoteDelegation ACS entry was missing JsActiveContract.createdEvent',
        'ledger'
      );
    }
    const payload = VoteDelegation.decoder.runWithException(createdEvent.createArgument);
    if (payload.voterParty !== voterPartyId) {
      return [];
    }
    return [
      {
        voteDelegationCid: createdEvent.contractId,
        svPartyId: payload.sv,
        voterPartyId,
      } satisfies DiscoveredVoteDelegation,
    ];
  });

  if (matches.length === 0) {
    throw new VoteDelegationDiscoveryError(
      `No VoteDelegation contract found for wallet party ${voterPartyId}. ` +
        'Ask the SV to create a VoteDelegation naming this party as voterParty.',
      'none'
    );
  }

  // TODO(#28): let the voter select which VoteDelegation to use when several match
  if (matches.length > 1) {
    const svList = matches.map(match => match.svPartyId).join(', ');
    throw new VoteDelegationDiscoveryError(
      `Found ${matches.length} VoteDelegation contracts for wallet party ${voterPartyId} ` +
        `(delegating SVs: ${svList}). Exactly one is required; archive extras or use a dedicated voter party.`,
      'ambiguous'
    );
  }

  return matches[0]!;
}

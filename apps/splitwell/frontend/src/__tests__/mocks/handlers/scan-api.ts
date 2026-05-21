// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { http, HttpHandler, HttpResponse } from 'msw';
import {
  ErrorResponse,
  LookupEntryByNameResponse,
  LookupEntryByPartyResponse,
  ListEntriesResponse,
  GetDsoPartyIdResponse,
} from '@lfdecentralizedtrust/scan-openapi';

import { alicePartyId, bobPartyId } from '../constants';

export const buildScanMock = (scanUrl: string): HttpHandler[] => [
  http.get(`${scanUrl}/v0/dso-party-id`, () => {
    return HttpResponse.json<GetDsoPartyIdResponse>({
      dso_party_id: 'DSO::1220809612f787469c92b924ad1d32f1cbc0bdbd4eeda55a50469250bcf64b8becf2',
    });
  }),
  http.get<{ partyId: string }, null, LookupEntryByPartyResponse | ErrorResponse>(
    `${scanUrl}/v0/ans-entries/by-party/:partyId`,
    ({ params }) => {
      if (params.partyId === alicePartyId) {
        return HttpResponse.json<LookupEntryByPartyResponse>({
          entry: {
            contract_id:
              '00c8e178f8b0b2c2955103b3fa59ccdc5f34861c4bcf659844c2959ba9febf3f61ca0212207e6c7b0db1b456c2f3f23c3b0c75b02dfc0c470cd1ea3fb603a01527e414c922',
            name: 'alice.unverified.tns',
            url: 'https://alice-url.tns.com',
            description: '',
            expires_at: new Date('2024-01-07T14:50:26.364476Z'),
            user: alicePartyId,
          },
        });
      }

      if (params.partyId === bobPartyId) {
        return HttpResponse.json<LookupEntryByPartyResponse>({
          entry: {
            contract_id:
              '00c8e178f8b0b2c2955103b3fa59ccdc5f34861c4bcf659844c2959ba9febf3f61ca0212207e6c7b0db1b456c2f3f23c3b0c75b02dfc0c470cd1ea3fb603a01527e414c922',
            name: 'bob.unverified.tns',
            url: 'https://bob-url.tns.com',
            description: '',
            expires_at: new Date('2024-01-07T14:50:26.364476Z'),
            user: bobPartyId,
          },
        });
      }

      return HttpResponse.json<ErrorResponse>(
        {
          error: `No tns entry found for party: ${alicePartyId}`,
        },
        { status: 404 }
      );
    }
  ),
  http.get<{ name: string }, null, LookupEntryByNameResponse | ErrorResponse>(
    `${scanUrl}/v0/ans-entries/by-name`,
    () => {
      return HttpResponse.json(
        {
          error: `No ans entry found for party: ${alicePartyId}`,
        },
        { status: 404 }
      );
    }
  ),
  http.get<{ partyId: string }, null, ListEntriesResponse>(`${scanUrl}/v0/ans-entries`, () => {
    return HttpResponse.json({
      entries: [],
    });
  }),
];

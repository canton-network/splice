// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  validatorLicensesHandler,
  dsoInfoHandler,
} from '@canton-network/splice-common-test-handlers';
import { http, HttpHandler, HttpResponse } from 'msw';
import { FeatureSupportResponse, SuccessStatusResponse } from '@canton-network/scan-openapi';
import {
  ListDsoRulesVoteRequestsResponse,
  ListFeaturedAppRightsByProviderResponse,
  ListOngoingValidatorOnboardingsResponse,
  LookupFeaturedAppRightByContractIdResponse,
  ListVoteRequestByTrackingCidResponse,
  LookupDsoRulesVoteRequestResponse,
  PrepareValidatorOnboardingResponse,
} from '@canton-network/sv-openapi';

import { voteRequest, voteRequests, svPartyId } from '../constants';
import { ValidatorOnboarding } from '@daml.js/splice-validator-lifecycle/lib/Splice/ValidatorOnboarding/module';
import { ContractId } from '@daml/types';

import { voteResultsHandlers } from './vote-results';

export const buildSvMock = (svUrl: string): HttpHandler[] => [
  http.get(`${svUrl}/v0/admin/authorization`, () => {
    return new HttpResponse(null, { status: 200 });
  }),

  dsoInfoHandler(svUrl),

  http.get(`${svUrl}/v0/admin/sv/voterequests`, () => {
    return HttpResponse.json<ListDsoRulesVoteRequestsResponse>(voteRequests);
  }),

  http.get(`${svUrl}/v0/admin/sv/voterequests/:id`, ({ params }) => {
    const { id } = params;
    return HttpResponse.json<LookupDsoRulesVoteRequestResponse>({
      dso_rules_vote_request: voteRequests.dso_rules_vote_requests.filter(
        vr => vr.contract_id === id
      )[0],
    });
  }),

  http.post(`${svUrl}/v0/admin/sv/voterequest/create`, () => {
    return HttpResponse.json({});

    // Use this to test a failed response
    // return res(
    //   ctx.status(503),
    //   ctx.json({
    //     error: 'Service Unavailable',
    //   })
    // );
  }),

  http.post(`${svUrl}/v0/admin/sv/voterequest`, () => {
    return HttpResponse.json<ListVoteRequestByTrackingCidResponse>(voteRequest);
  }),

  ...voteResultsHandlers(svUrl),

  http.post(`${svUrl}/v0/admin/sv/votes`, () => {
    return new HttpResponse(null, { status: 201 });
  }),

  http.get(`${svUrl}/v0/admin/domain/sequencer/status`, () => {
    return HttpResponse.json<SuccessStatusResponse>({
      success: {
        id: 'global-domain::1990be58c99e65de40bf273be1dc2b266d43a9a002ea5b18955aeef7aac881bb999a',
        uptime: 'PT26H38.219973S',
        ports: {
          public: 5008,
          admin: 5009,
        },
        active: true,
      },
    });
  }),

  http.get(`${svUrl}/v0/admin/domain/mediator/status`, () => {
    return HttpResponse.json<SuccessStatusResponse>({
      success: {
        id: 'global-domain::1990be58c99e65de40bf273be1dc2b266d43a9a002ea5b18955aeef7aac881bb999a',
        uptime: 'PT26H38.219973S',
        ports: {
          public: 5008,
          admin: 5009,
        },
        active: true,
      },
    });
  }),

  http.get(`${svUrl}/v0/admin/feature-support`, () => {
    return HttpResponse.json<FeatureSupportResponse>({});
  }),

  validatorLicensesHandler(svUrl),
  http.post(`${svUrl}/v0/admin/validator/onboarding/prepare`, () => {
    return HttpResponse.json<PrepareValidatorOnboardingResponse>({ secret: 'new_encoded_secret' });
  }),
  http.get(`${svUrl}/v0/admin/validator/onboarding/ongoing`, () => {
    return HttpResponse.json<ListOngoingValidatorOnboardingsResponse>({
      ongoing_validator_onboardings: [
        {
          encoded_secret: 'encoded_secret',
          contract: {
            template_id:
              '455dd4533c2dd0131fb349c93d9d35f3670901d13efadb0aa9b975d35b41dbb2:Splice.ValidatorOnboarding:ValidatorOnboarding',
            contract_id: 'validatorOnboardingCid' as ContractId<ValidatorOnboarding>,
            payload: {
              sv: 'svParty',
              candidateSecret: 'candidate_secret',
              expiresAt: '2024-08-05T13:44:35.878681Z',
            },
            created_event_blob: '',
            created_at: '2024-08-05T13:44:35.878681Z',
          },
        },
      ],
    });
  }),

  http.get(`${svUrl}/v0/admin/sv/party-to-participant/:partyId`, ({ params }) => {
    const normalizedPartyId = decodeURIComponent(String(params.partyId));

    if (normalizedPartyId === 'a-party-id::1014912492' || normalizedPartyId === svPartyId) {
      return HttpResponse.json({
        participant_ids: [svPartyId],
      });
    } else {
      return new HttpResponse(null, { status: 404 });
    }
  }),

  http.get(
    `${svUrl}/v0/admin/sv/featured-app-rights/by-provider/:providerPartyId`,
    ({ params }) => {
      const providerPartyId = decodeURIComponent(String(params.providerPartyId));
      const featuredAppRights =
        providerPartyId === 'a-party-id::1014912492'
          ? [
              {
                template_id: 'featured-app-right-template-id',
                contract_id: 'rightCid123',
                payload: { activityWeight: '1.0' },
                created_event_blob: '',
                created_at: '2026-02-26T13:00:00.000000Z',
              },
            ]
          : [];

      return HttpResponse.json<ListFeaturedAppRightsByProviderResponse>({
        featured_app_rights: featuredAppRights,
      });
    }
  ),

  http.get(`${svUrl}/v0/admin/sv/featured-app-rights/by-contract-id/:contractId`, ({ params }) => {
    const contractId = decodeURIComponent(String(params.contractId));
    const featuredAppRight =
      contractId === 'rightCid123'
        ? {
            template_id: 'featured-app-right-template-id',
            contract_id: 'rightCid123',
            payload: { provider: 'a-party-id::1014912492', activityWeight: '1.0' },
            created_event_blob: '',
            created_at: '2026-02-26T13:00:00.000000Z',
          }
        : undefined;

    return HttpResponse.json<LookupFeaturedAppRightByContractIdResponse>({
      featured_app_right: featuredAppRight,
    });
  }),
];

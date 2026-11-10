// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { dsoInfo, dsoInfoHandler } from '@canton-network/splice-common-test-handlers';
import { http, HttpHandler, HttpResponse } from 'msw';
import {
  FeatureSupportResponse,
  GetSvInfoResponse,
  ListAmuletPriceVotesResponse,
  ListDsoRulesVoteRequestsResponse,
  ListVoteRequestByTrackingCidResponse,
  LookupDsoRulesVoteRequestResponse,
  LookupFeaturedAppRightResponse,
  PreviousSvRewardWeightResponse,
} from '@canton-network/scan-openapi';

import { voteRequest, voteRequests } from '../constants';
import { voteResultsHandlers } from './vote-results';

/** Scan-side counterparts of the governance read endpoints mocked in `sv-api.ts`, serving the same data. */
export const buildScanMock = (scanUrl: string): HttpHandler[] => [
  dsoInfoHandler(scanUrl),

  http.get(`${scanUrl}/v0/sv-info`, () => {
    return HttpResponse.json<GetSvInfoResponse>({
      sv_user: 'scan-sv',
      sv_party_id: dsoInfo.sv_party_id,
    });
  }),

  http.get(`${scanUrl}/v0/feature-support`, () => {
    return HttpResponse.json<FeatureSupportResponse>({});
  }),

  http.get(`${scanUrl}/v0/admin/sv/voterequests`, () => {
    return HttpResponse.json<ListDsoRulesVoteRequestsResponse>(voteRequests);
  }),

  http.get(`${scanUrl}/v0/voterequests/:id`, ({ params }) => {
    const { id } = params;
    return HttpResponse.json<LookupDsoRulesVoteRequestResponse>({
      dso_rules_vote_request: voteRequests.dso_rules_vote_requests.filter(
        vr => vr.contract_id === id
      )[0],
    });
  }),

  http.post(`${scanUrl}/v0/voterequest`, () => {
    return HttpResponse.json<ListVoteRequestByTrackingCidResponse>(voteRequest);
  }),

  ...voteResultsHandlers(scanUrl),

  http.post(`${scanUrl}/v0/admin/sv/previous-sv-reward-weight`, () => {
    return HttpResponse.json<PreviousSvRewardWeightResponse>({});
  }),

  http.get(`${scanUrl}/v0/amulet-price/votes`, () => {
    return HttpResponse.json<ListAmuletPriceVotesResponse>({ amulet_price_votes: [] });
  }),

  http.get(`${scanUrl}/v0/featured-apps/by-contract-id/:contractId`, () => {
    return HttpResponse.json<LookupFeaturedAppRightResponse>({});
  }),
];

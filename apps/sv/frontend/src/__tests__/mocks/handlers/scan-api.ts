// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { dsoInfoHandler } from '@canton-network/splice-common-test-handlers';
import dayjs from 'dayjs';
import { http, HttpHandler, HttpResponse, PathParams } from 'msw';
import {
  CountVoteResultsRequest,
  CountVoteResultsResponse,
  FeatureSupportResponse,
  ListDsoRulesVoteRequestsResponse,
  ListDsoRulesVoteResultsResponse,
  ListVoteRequestByTrackingCidResponse,
  ListVoteResultsRequest,
  LookupDsoRulesVoteRequestResponse,
} from '@canton-network/scan-openapi';

import {
  voteRequest,
  voteRequests,
  voteResultsAmuletRules,
  voteResultsDsoRules,
} from '../constants';

/**
 * Scan API mock used by dApp-mode tests. Serves the same governance fixtures
 * as the SV API mock (the response schemas are shared between the two APIs).
 */
export const buildScanMock = (scanUrl: string): HttpHandler[] => [
  dsoInfoHandler(scanUrl),

  http.get(`${scanUrl}/v0/feature-support`, () => {
    return HttpResponse.json<FeatureSupportResponse>({});
  }),

  http.get(`${scanUrl}/v0/admin/sv/voterequests`, () => {
    return HttpResponse.json<ListDsoRulesVoteRequestsResponse>(voteRequests);
  }),

  http.get(`${scanUrl}/v0/voterequests/:id`, ({ params }) => {
    const { id } = params;
    const match = voteRequests.dso_rules_vote_requests.find(vr => vr.contract_id === id);
    if (!match) {
      return HttpResponse.json({ error: 'VoteRequest contract not found.' }, { status: 404 });
    }
    return HttpResponse.json<LookupDsoRulesVoteRequestResponse>({
      dso_rules_vote_request: match,
    });
  }),

  http.post(`${scanUrl}/v0/voterequest`, () => {
    return HttpResponse.json<ListVoteRequestByTrackingCidResponse>(voteRequest);
  }),

  http.post<PathParams, ListVoteResultsRequest>(
    `${scanUrl}/v0/admin/sv/voteresults`,
    ({ request }) => {
      return request.json().then(data => {
        const allResults = voteResultsAmuletRules.dso_rules_vote_results
          .concat(voteResultsDsoRules.dso_rules_vote_results)
          .filter(r => {
            const acceptedMatch =
              data.accepted === undefined || data.accepted === null
                ? true
                : data.accepted
                  ? r.outcome.tag === 'VRO_Accepted'
                  : r.outcome.tag === 'VRO_Rejected';
            const effectiveToMatch = data.effectiveTo
              ? r.outcome.value
                ? dayjs(r.outcome.value.effectiveAt).isBefore(dayjs(data.effectiveTo))
                : dayjs(r.completedAt).isBefore(dayjs(data.effectiveTo))
              : true;
            const effectiveFromMatch = data.effectiveFrom
              ? r.outcome.value
                ? dayjs(r.outcome.value.effectiveAt).isAfter(dayjs(data.effectiveFrom))
                : dayjs(r.completedAt).isAfter(dayjs(data.effectiveFrom))
              : true;
            return acceptedMatch && effectiveToMatch && effectiveFromMatch;
          });
        // Cursor-based pagination with descending synthetic entry numbers,
        // mirroring the SV API mock.
        const total = allResults.length;
        const cursor = data.pageToken;
        const startIndex =
          cursor !== undefined && cursor !== null
            ? allResults.findIndex((_, i) => total - i < cursor)
            : 0;
        const limit = data.limit || 10;
        const paged = startIndex === -1 ? [] : allResults.slice(startIndex, startIndex + limit);
        const lastEntryNumber =
          paged.length > 0 ? total - (startIndex + paged.length - 1) : undefined;
        const hasMore = startIndex !== -1 && startIndex + paged.length < total;
        return HttpResponse.json<ListDsoRulesVoteResultsResponse>({
          dso_rules_vote_results: paged,
          ...(hasMore && lastEntryNumber !== undefined ? { next_page_token: lastEntryNumber } : {}),
        });
      });
    }
  ),

  http.post<PathParams, CountVoteResultsRequest>(
    `${scanUrl}/v0/admin/sv/voteresults/count`,
    ({ request }) => {
      return request.json().then(data => {
        const count = voteResultsAmuletRules.dso_rules_vote_results
          .concat(voteResultsDsoRules.dso_rules_vote_results)
          .filter(r => {
            const isAccepted = r.outcome.tag === 'VRO_Accepted';
            const acceptedMatch =
              data.accepted === undefined || data.accepted === null
                ? true
                : data.accepted === isAccepted;
            const effectiveToMatch = data.effectiveTo
              ? isAccepted && dayjs(r.outcome.value.effectiveAt).isBefore(dayjs(data.effectiveTo))
              : true;
            return acceptedMatch && effectiveToMatch;
          }).length;
        return HttpResponse.json<CountVoteResultsResponse>({ count });
      });
    }
  ),
];

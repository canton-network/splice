// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import dayjs from 'dayjs';
import { http, HttpHandler, HttpResponse, PathParams } from 'msw';
import {
  CountVoteResultsRequest,
  CountVoteResultsResponse,
  ListDsoRulesVoteResultsResponse,
  ListVoteResultsRequest,
} from '@canton-network/sv-openapi';

import { voteResultsAmuletRules, voteResultsDsoRules } from '../constants';

/**
 * Handlers for `POST /v0/admin/sv/voteresults` and `POST /v0/admin/sv/voteresults/count`,
 * which the SV app and Scan serve under the same paths. Shared so both mocks filter,
 * paginate and count the same vote results.
 */
export const voteResultsHandlers = (baseUrl: string): HttpHandler[] => [
  http.post<PathParams, ListVoteResultsRequest>(
    `${baseUrl}/v0/admin/sv/voteresults`,
    ({ request }) => {
      return request.json().then(data => {
        if (data.actionName === 'SRARC_SetConfig') {
          return HttpResponse.json<ListDsoRulesVoteResultsResponse>({
            dso_rules_vote_results: voteResultsDsoRules.dso_rules_vote_results
              .filter(
                r =>
                  (data.accepted
                    ? r.outcome.tag === 'VRO_Accepted'
                    : r.outcome.tag === 'VRO_Rejected') &&
                  (data.effectiveTo
                    ? dayjs(r.completedAt).isBefore(dayjs(data.effectiveTo))
                    : true) &&
                  (data.effectiveFrom
                    ? dayjs(r.completedAt).isAfter(dayjs(data.effectiveFrom))
                    : true)
              )
              .slice(0, data.limit || 10),
          });
        } else if (data.actionName === 'CRARC_AddFutureAmuletConfigSchedule') {
          return HttpResponse.json<ListDsoRulesVoteResultsResponse>({
            dso_rules_vote_results: voteResultsAmuletRules.dso_rules_vote_results
              .filter(
                r =>
                  (data.accepted
                    ? r.outcome.tag === 'VRO_Accepted'
                    : r.outcome.tag === 'VRO_Rejected') &&
                  (data.effectiveTo
                    ? r.outcome.value
                      ? dayjs(r.outcome.value.effectiveAt).isBefore(dayjs(data.effectiveTo))
                      : dayjs(r.completedAt).isBefore(dayjs(data.effectiveTo))
                    : true) &&
                  (data.effectiveFrom
                    ? r.outcome.value
                      ? dayjs(r.outcome.value.effectiveAt).isAfter(dayjs(data.effectiveFrom))
                      : dayjs(r.completedAt).isAfter(dayjs(data.effectiveFrom))
                    : true)
              )
              .slice(0, data.limit || 10),
          });
        } else if (data.actionName === 'CRARC_UpdateFutureAmuletConfigSchedule') {
          return HttpResponse.json<ListDsoRulesVoteResultsResponse>({
            dso_rules_vote_results: [],
          });
        } else {
          // Simulate cursor-based pagination using descending synthetic entry_numbers.
          // Each result is assigned an entry_number equal to (total - index), so the
          // first result has the highest entry_number and the last has entry_number 1.
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
          const total = allResults.length;
          const cursor = data.pageToken;
          // Find the starting index: skip results whose entry_number >= cursor
          const startIndex =
            cursor !== undefined && cursor !== null
              ? allResults.findIndex((_, i) => total - i < cursor)
              : 0;
          const limit = data.limit || 10;
          const paged = allResults.slice(startIndex, startIndex + limit);
          const lastEntryNumber =
            paged.length > 0 ? total - (startIndex + paged.length - 1) : undefined;
          const hasMore = startIndex + paged.length < total;
          return HttpResponse.json<ListDsoRulesVoteResultsResponse>({
            dso_rules_vote_results: paged,
            ...(hasMore && lastEntryNumber !== undefined
              ? { next_page_token: lastEntryNumber }
              : {}),
          });
        }
      });
    }
  ),

  http.post<PathParams, CountVoteResultsRequest>(
    `${baseUrl}/v0/admin/sv/voteresults/count`,
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

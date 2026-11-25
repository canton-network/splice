// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as openapi from '@canton-network/scan-openapi';

import { GovernanceReadClient } from '../contexts/GovernanceReadClientContext';

/**
 * Serves the governance read API from Scan instead of the SV app.
 * Argument-to-request mapping mirrors the operator implementation in `SvAdminServiceContext.tsx`.
 */
export const scanGovernanceReadClient = (scan: openapi.ScanApi): GovernanceReadClient => ({
  listDsoRulesVoteRequests: () => scan.listDsoRulesVoteRequests(),
  listVoteRequestResults: (
    limit,
    actionName,
    requester,
    effectiveFrom,
    effectiveTo,
    accepted,
    pageToken
  ) =>
    scan.listVoteRequestResults({
      actionName,
      accepted,
      requester,
      effectiveFrom,
      effectiveTo,
      limit,
      pageToken,
    }),
  countVoteRequestResults: (accepted, effectiveTo) =>
    scan.countVoteRequestResults({ accepted, effectiveTo }),
  lookupDsoRulesVoteRequest: voteRequestContractId =>
    scan.lookupDsoRulesVoteRequest(voteRequestContractId),
  listVoteRequestsByTrackingCid: voteRequestContractIds =>
    scan.listVoteRequestsByTrackingCid({ vote_request_contract_ids: voteRequestContractIds }),
  getPreviousSvRewardWeight: (svParty, effectiveBefore) =>
    scan.getPreviousSvRewardWeight({ svParty, effectiveBefore }),
  lookupFeaturedAppRightByContractId: contractId =>
    scan.lookupFeaturedAppRightByContractId(contractId),
  listAmuletPriceVotes: () => scan.listAmuletPriceVotes(),
  featureSupport: () => scan.featureSupport(),
  // TODO(#8296): add the viewer's SV identity (sv_user, sv_party_id): the SV the wallet user
  // chose from their delegations. Until then there is no viewer and no "you" in the UI.
  getDsoInfo: () => scan.getDsoInfoV1(),
});

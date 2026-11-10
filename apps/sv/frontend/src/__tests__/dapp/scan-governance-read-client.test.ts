// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as openapi from '@canton-network/scan-openapi';
import { describe, expect, test } from 'vitest';
import { scanGovernanceReadClient } from '../../dapp/scanGovernanceReadClient';
import { scanUrl } from '../setup/setup';
import {
  activeProposalCid,
  svPartyId,
  voteResultsAmuletRules,
  voteResultsDsoRules,
} from '../mocks/constants';

const client = scanGovernanceReadClient(
  new openapi.ScanApi(
    openapi.createConfiguration({ baseServer: new openapi.ServerConfiguration(scanUrl, {}) })
  )
);

describe('scanGovernanceReadClient', () => {
  test('scan client dsoInfo merges dso and sv info', async () => {
    const info = await client.getDsoInfo();
    expect(info.sv_party_id).toBe(svPartyId);
    expect(info.sv_user).toBe('scan-sv');
    expect(info.dso_rules.contract.template_id).toContain('DsoRules');
  });
  test('lists vote requests', async () => {
    const r = await client.listDsoRulesVoteRequests();
    expect(r.dso_rules_vote_requests.map(c => c.contract_id)).toContain(activeProposalCid);
  });
  test('paginates vote results', async () => {
    const r = await client.listVoteRequestResults(
      20,
      undefined,
      undefined,
      undefined,
      undefined,
      true
    );
    expect(r.dso_rules_vote_results.length).toBeGreaterThan(0);
  });
  test('counts vote results', async () => {
    const acceptedCount = voteResultsAmuletRules.dso_rules_vote_results
      .concat(voteResultsDsoRules.dso_rules_vote_results)
      .filter(v => v.outcome.tag === 'VRO_Accepted').length;
    const r = await client.countVoteRequestResults(true);
    expect(r.count).toBe(acceptedCount);
  });
});

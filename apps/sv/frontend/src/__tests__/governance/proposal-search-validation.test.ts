// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, test } from 'vitest';
import { activeProposalCid, closedVoteCid } from '../mocks/constants';
import { isValidContractId } from '../../utils/proposalSearch';

describe('isValidContractId', () => {
  test.each([
    [activeProposalCid, true],
    [closedVoteCid, true],
    [`00${'ab'.repeat(32)}`, true],
    [`01${'ab'.repeat(12)}`, true],
    ['', false],
    ['not-a-valid-contract-id', false],
    [`00${'a'.repeat(65)}`, false],
    [`00${'ab'.repeat(31)}`, false],
    [`01${'a'.repeat(25)}`, false],
    ['00', false],
  ])('%p -> %s', (value, expected) => {
    expect(isValidContractId(value)).toBe(expected);
  });
});

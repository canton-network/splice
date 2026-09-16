// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, test } from 'vitest';
import type { SvInfo } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { getRequesterPartyId } from '../../utils/governance';

const partyId = 'digital-asset-2::1220abc';
const svName = 'Digital-Asset-2';

const svs = {
  entriesArray: () => [[partyId, { name: svName } as SvInfo] as [string, SvInfo]],
};

describe('getRequesterPartyId', () => {
  test('returns requester unchanged when it is already a party id', () => {
    expect(getRequesterPartyId(partyId, svs)).toBe(partyId);
  });

  test('resolves sv name to party id', () => {
    expect(getRequesterPartyId(svName, svs)).toBe(partyId);
  });

  test('returns requester when svs is undefined', () => {
    expect(getRequesterPartyId(svName, undefined)).toBe(svName);
  });

  test('returns requester when sv is not in svs (e.g. offboarded)', () => {
    expect(getRequesterPartyId('Offboarded-SV', svs)).toBe('Offboarded-SV');
  });
});

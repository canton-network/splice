// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, it, expect } from 'vitest';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import { validateSwitchOverTimes } from '../components/forms/formValidators';

dayjs.extend(utc);

describe('validateSwitchOverTimes', () => {
  const eff = '2026-09-01T00:00:00Z';

  it('passes with no entries', () => {
    expect(validateSwitchOverTimes([], false, eff)).toBe(false);
  });

  it('rejects an empty key', () => {
    expect(
      validateSwitchOverTimes([{ key: '  ', time: '2026-09-05T00:00:00Z' }], false, eff)
    ).toBeTruthy();
  });

  it('rejects duplicate keys', () => {
    expect(
      validateSwitchOverTimes(
        [
          { key: 'a', time: '2026-09-05T00:00:00Z' },
          { key: 'a', time: '2026-09-06T00:00:00Z' },
        ],
        false,
        eff
      )
    ).toBeTruthy();
  });

  it('rejects a time less than 1 day after effectivity', () => {
    expect(
      validateSwitchOverTimes([{ key: 'a', time: '2026-09-01T12:00:00Z' }], false, eff)
    ).toBeTruthy();
  });

  it('accepts a time exactly 1 day after effectivity (inclusive)', () => {
    expect(validateSwitchOverTimes([{ key: 'a', time: '2026-09-02T00:00:00Z' }], false, eff)).toBe(
      false
    );
  });

  it('accepts a time more than 1 day after effectivity', () => {
    expect(validateSwitchOverTimes([{ key: 'a', time: '2026-09-10T00:00:00Z' }], false, eff)).toBe(
      false
    );
  });

  it('accepts a past time when non-future-dated is allowed', () => {
    expect(validateSwitchOverTimes([{ key: 'a', time: '2020-01-01T00:00:00Z' }], true, eff)).toBe(
      false
    );
  });

  it('skips the 1-day check at threshold (no effective date)', () => {
    expect(
      validateSwitchOverTimes([{ key: 'a', time: '2020-01-01T00:00:00Z' }], false, undefined)
    ).toBe(false);
  });
});

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, it, expect } from 'vitest';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import {
  serializeSwitchOverTimes,
  switchOverTimesChanged,
  validateSwitchOverTimes,
} from '../components/forms/formValidators';

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

describe('serializeSwitchOverTimes', () => {
  it('returns null for no entries', () => {
    expect(serializeSwitchOverTimes([])).toBeNull();
  });

  it('drops entries with an empty (or whitespace) key and trims keys', () => {
    expect(
      serializeSwitchOverTimes([
        { key: '  ', time: '2026-09-05T00:00:00Z' },
        { key: '  a  ', time: '2026-09-06T00:00:00Z' },
      ])
    ).toEqual({ a: '2026-09-06T00:00:00Z' });
  });

  it('returns null when every entry has an empty key', () => {
    expect(serializeSwitchOverTimes([{ key: '  ', time: '2026-09-05T00:00:00Z' }])).toBeNull();
  });

  it('normalizes times to the DAML Time format', () => {
    expect(serializeSwitchOverTimes([{ key: 'a', time: '2026-09-05 12:34' }])).toEqual({
      a: '2026-09-05T12:34:00Z',
    });
  });
});

describe('switchOverTimesChanged', () => {
  it('is false when baseline and entries are both empty', () => {
    expect(switchOverTimesChanged(null, [])).toBe(false);
    expect(switchOverTimesChanged(undefined, [])).toBe(false);
    expect(switchOverTimesChanged({}, [])).toBe(false);
  });

  it('is false when entries equal the baseline', () => {
    expect(
      switchOverTimesChanged({ a: '2026-09-05T00:00:00Z' }, [
        { key: 'a', time: '2026-09-05T00:00:00Z' },
      ])
    ).toBe(false);
  });

  it('is false regardless of entry order (map semantics)', () => {
    const baseline = { a: '2026-09-05T00:00:00Z', b: '2026-09-06T00:00:00Z' };
    expect(
      switchOverTimesChanged(baseline, [
        { key: 'b', time: '2026-09-06T00:00:00Z' },
        { key: 'a', time: '2026-09-05T00:00:00Z' },
      ])
    ).toBe(false);
  });

  it('ignores blank-key rows that serialize away', () => {
    expect(
      switchOverTimesChanged({ a: '2026-09-05T00:00:00Z' }, [
        { key: 'a', time: '2026-09-05T00:00:00Z' },
        { key: '  ', time: '2026-09-07T00:00:00Z' },
      ])
    ).toBe(false);
  });

  it('detects an added entry', () => {
    expect(switchOverTimesChanged(null, [{ key: 'a', time: '2026-09-05T00:00:00Z' }])).toBe(true);
  });

  it('detects a removed entry', () => {
    expect(switchOverTimesChanged({ a: '2026-09-05T00:00:00Z' }, [])).toBe(true);
  });

  it('detects a changed time for the same key', () => {
    expect(
      switchOverTimesChanged({ a: '2026-09-05T00:00:00Z' }, [
        { key: 'a', time: '2026-09-06T00:00:00Z' },
      ])
    ).toBe(true);
  });

  it('detects a renamed key', () => {
    expect(
      switchOverTimesChanged({ a: '2026-09-05T00:00:00Z' }, [
        { key: 'b', time: '2026-09-05T00:00:00Z' },
      ])
    ).toBe(true);
  });
});

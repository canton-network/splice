// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { damlTimestampToOpenApiTimestamp } from '../utils/timestampConversion';

describe('damlTimestampToOpenApiTimestamp', () => {
  const jan0124 = new Date('2024-01-01T00:00:00Z').valueOf() * 1000;
  const jun1524 = new Date('2024-06-15T00:00:00Z').valueOf() * 1000;
  const may1126noon = new Date('2026-05-11T12:00:00Z').valueOf() * 1000;
  const timestamps: Record<string, number> = {
    '2024-01-01T00:00:00.123456Z': jan0124 + 123456,
    '2024-01-01T00:00:00Z': jan0124,
    '2024-01-01T00:00:00.500Z': jan0124 + 500000,
    '2024-01-01T00:00:00.1Z': jan0124 + 100000,
    '2024-06-15T12:30:00Z': jun1524 + (12 * 60 + 30) * 60 * 1000000,
    '2024-01-01T00:00:00.000001Z': jan0124 + 1,
    '2026-05-11T12:00:00Z': may1126noon,
    '2026-05-11T12:00:00.000000Z': may1126noon,
    '2026-05-11T12:00:00.123Z': may1126noon + 123000,
    '2026-05-11T12:00:00.123456Z': may1126noon + 123456,
    // just drop after micros, Daml won't produce them anyway
    '2026-05-11T12:00:00.123456789Z': may1126noon + 123456,
  };

  it.each(Object.entries(timestamps))('%s -> %d', (input, expected) => {
    expect(damlTimestampToOpenApiTimestamp(input)).toBe(expected);
  });
});

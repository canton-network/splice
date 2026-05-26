// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, test } from 'vitest';

import { Algorithm, authSchema } from '../../config/schema';

const rs256Base = {
  algorithm: Algorithm.RS256,
  authority: 'https://idp.example.com/',
  client_id: 'test-client',
  token_audience: 'test-aud',
  token_scope: 'wallet',
};

describe('authSchema rs256 enable_offline_scope', () => {
  test('omitted field parses', () => {
    const r = authSchema.safeParse(rs256Base);
    expect(r.success).toBe(true);
  });

  test('explicit true parses', () => {
    const r = authSchema.safeParse({ ...rs256Base, enable_offline_scope: true });
    expect(r.success).toBe(true);
  });

  test('explicit false parses', () => {
    const r = authSchema.safeParse({ ...rs256Base, enable_offline_scope: false });
    expect(r.success).toBe(true);
  });

  test('string "true" is rejected (no zod coercion)', () => {
    const r = authSchema.safeParse({ ...rs256Base, enable_offline_scope: 'true' });
    expect(r.success).toBe(false);
  });

  test('numeric 1 is rejected (no zod coercion)', () => {
    const r = authSchema.safeParse({ ...rs256Base, enable_offline_scope: 1 });
    expect(r.success).toBe(false);
  });

  test('strict() still rejects unknown keys on rs256', () => {
    const r = authSchema.safeParse({ ...rs256Base, totally_unknown_field: true });
    expect(r.success).toBe(false);
  });

  test('field is rejected on hs256-unsafe config (strict)', () => {
    const r = authSchema.safeParse({
      algorithm: Algorithm.HS256UNSAFE,
      secret: 'shh',
      token_audience: 'test-aud',
      enable_offline_scope: true,
    });
    expect(r.success).toBe(false);
  });
});

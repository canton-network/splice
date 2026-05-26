// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, test } from 'vitest';

import { isUnauthorizedError, retryQuery } from '../../utils/helpers';

describe('isUnauthorizedError', () => {
  test('detects 401 on openapi-shaped error (code field)', () => {
    const err = Object.assign(new Error('Unauthorized'), { code: 401 });
    expect(isUnauthorizedError(err)).toBe(true);
  });

  test('detects 401 on LedgerApi-shaped error (status field)', () => {
    const err = Object.assign(new Error('Unauthorized'), { status: 401 });
    expect(isUnauthorizedError(err)).toBe(true);
  });

  test.each([200, 400, 403, 404, 500])('returns false for status %i', status => {
    const openapiErr = Object.assign(new Error('boom'), { code: status });
    const ledgerErr = Object.assign(new Error('boom'), { status });
    expect(isUnauthorizedError(openapiErr)).toBe(false);
    expect(isUnauthorizedError(ledgerErr)).toBe(false);
  });

  test('returns false for plain Error without status fields', () => {
    expect(isUnauthorizedError(new Error('boom'))).toBe(false);
  });
});

describe('retryQuery', () => {
  const unauthorized = Object.assign(new Error('Unauthorized'), { code: 401 });
  const networkErr = new Error('Network failure');

  test('returns false on 401 even at failureCount 0', () => {
    expect(retryQuery(0, unauthorized)).toBe(false);
  });

  test('returns false on 401 at any failureCount', () => {
    expect(retryQuery(5, unauthorized)).toBe(false);
  });

  test('returns true for non-401 errors below the retry cap', () => {
    expect(retryQuery(0, networkErr)).toBe(true);
    expect(retryQuery(1, networkErr)).toBe(true);
    expect(retryQuery(2, networkErr)).toBe(true);
  });

  test('returns false for non-401 errors at or above the retry cap', () => {
    expect(retryQuery(3, networkErr)).toBe(false);
    expect(retryQuery(10, networkErr)).toBe(false);
  });
});

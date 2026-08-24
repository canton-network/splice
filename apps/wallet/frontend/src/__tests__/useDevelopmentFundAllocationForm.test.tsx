// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import BigNumber from 'bignumber.js';
import dayjs, { Dayjs } from 'dayjs';
import { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

import useGetAmuletRules from '../hooks/scan-proxy/useGetAmuletRules';
import {
  useDevelopmentFundAllocationForm,
  UseDevelopmentFundAllocationFormResult,
} from '../hooks/useDevelopmentFundAllocationForm';
import { alicePartyId } from './mocks/constants';

vi.mock('../hooks/scan-proxy/useGetAmuletRules', () => ({ default: vi.fn() }));

vi.mock('../hooks/useIsDevelopmentFundManager', () => ({
  useIsDevelopmentFundManager: () => ({ isFundManager: true, isLoading: false }),
}));

vi.mock('../hooks/useUnclaimedDevelopmentFundTotal', () => ({
  useUnclaimedDevelopmentFundTotal: () => ({
    data: new BigNumber(100),
    isLoading: false,
    isError: false,
    error: null,
    invalidate: vi.fn(),
  }),
}));

vi.mock('../contexts/WalletServiceContext', () => ({
  useWalletClient: () => ({ allocateDevelopmentFundCoupon: vi.fn() }),
}));

const openedAt = new Date('2026-01-15T12:00:00.000Z');
const sevenDaysInMicros = String(7 * 24 * 60 * 60 * 1_000_000);

const mockMintingDelay = (minDevelopmentFundMintingDelay: { microseconds: string } | null) => {
  vi.mocked(useGetAmuletRules).mockReturnValue({
    data: {
      contract: {
        payload: { configSchedule: { initialValue: { minDevelopmentFundMintingDelay } } },
      },
    },
  } as unknown as ReturnType<typeof useGetAmuletRules>);
};

const renderForm = () => {
  const queryClient = new QueryClient();
  return renderHook(() => useDevelopmentFundAllocationForm(), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    ),
  });
};

const fillRequiredFields = (
  result: { current: UseDevelopmentFundAllocationFormResult },
  expiresAt: Dayjs
) =>
  act(() => {
    result.current.setBeneficiary(alicePartyId);
    result.current.setAmount('1');
    result.current.setExpiresAt(expiresAt);
    result.current.setReason('Valid allocation');
  });

describe('useDevelopmentFundAllocationForm', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(openedAt);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // Happy
  test('treats an absent minting delay as a zero delay and still prefills mintAfter', () => {
    mockMintingDelay(null);

    const { result } = renderForm();
    fillRequiredFields(result, dayjs(openedAt).add(2, 'day'));

    expect(result.current.minMintAfter.toISOString()).toBe('2026-01-15T12:00:00.000Z');
    expect(result.current.mintAfter?.toISOString()).toBe('2026-01-15T13:00:00.000Z');
    expect(result.current.mintAfterError).toBeUndefined();
    expect(result.current.isValid).toBe(true);
  });

  // Happy
  test('offsets minMintAfter by the configured minting delay', () => {
    mockMintingDelay({ microseconds: sevenDaysInMicros });

    const { result } = renderForm();
    fillRequiredFields(result, dayjs(openedAt).add(30, 'day'));

    expect(result.current.minMintAfter.toISOString()).toBe('2026-01-22T12:00:00.000Z');
    expect(result.current.mintAfter?.toISOString()).toBe('2026-01-22T13:00:00.000Z');
    expect(result.current.mintAfterError).toBeUndefined();
    expect(result.current.isValid).toBe(true);
  });

  // Unhappy
  test('requires a mintAfter after the fund manager clears the field', () => {
    mockMintingDelay(null);

    const { result } = renderForm();
    fillRequiredFields(result, dayjs(openedAt).add(2, 'day'));
    act(() => result.current.setMintAfter(null));

    expect(result.current.mintAfterError).toBe('Mint after is required');
    expect(result.current.isMintAfterValid).toBe(false);
    expect(result.current.isValid).toBe(false);
  });

  // Unhappy
  test('rejects a mintAfter that fell into the past while the form stayed open', () => {
    mockMintingDelay(null);

    const { result } = renderForm();
    vi.setSystemTime(dayjs(openedAt).add(2, 'hour').toDate());
    act(() => result.current.setMintAfter(dayjs(openedAt).add(1, 'hour')));

    expect(result.current.mintAfterError).toBe('Mint after must be in the future');
    expect(result.current.isMintAfterValid).toBe(false);
  });

  // Unhappy
  test('rejects a mintAfter earlier than the configured minting delay allows', () => {
    mockMintingDelay({ microseconds: sevenDaysInMicros });

    const { result } = renderForm();
    act(() => result.current.setMintAfter(dayjs(openedAt).add(1, 'day')));

    expect(result.current.mintAfterError).toMatch(/^Mint after must be at or after /);
    expect(result.current.isMintAfterValid).toBe(false);
  });

  // Unhappy
  test('rejects a mintAfter that is not before the expiry', () => {
    mockMintingDelay(null);

    const { result } = renderForm();
    fillRequiredFields(result, dayjs(openedAt).add(2, 'day'));
    act(() => result.current.setMintAfter(dayjs(openedAt).add(3, 'day')));

    expect(result.current.mintAfterError).toBe('Mint after must be before the expiry');
    expect(result.current.isValid).toBe(false);
  });
});

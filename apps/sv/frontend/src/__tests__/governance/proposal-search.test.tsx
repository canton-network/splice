// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router';
import { ProposalSearch } from '../../components/governance/ProposalSearch';
import { activeProposalCid } from '../mocks/constants';

const SearchHarness: React.FC<{ onSearchChange: (query: string) => void }> = ({
  onSearchChange,
}) => {
  const [searchParams, setSearchParams] = useSearchParams();

  return (
    <>
      <ProposalSearch onSearchChange={onSearchChange} />
      <span data-testid="url-q">{searchParams.get('q') ?? ''}</span>
      <button
        type="button"
        data-testid="set-stale-url"
        onClick={() => setSearchParams({ q: 'a' }, { replace: true })}
      >
        stale url
      </button>
    </>
  );
};

describe('ProposalSearch', () => {
  test('does not reset input when stale URL update arrives while typing ahead', async () => {
    const onSearchChange = vi.fn();
    render(
      <MemoryRouter initialEntries={['/governance']}>
        <Routes>
          <Route path="/governance" element={<SearchHarness onSearchChange={onSearchChange} />} />
        </Routes>
      </MemoryRouter>
    );

    const input = screen.getByTestId('proposal-search-input');
    fireEvent.change(input, { target: { value: 'abc' } });

    expect(input).toHaveValue('abc');
    expect(onSearchChange).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('set-stale-url'));

    expect(input).toHaveValue('abc');
    expect(onSearchChange).not.toHaveBeenCalled();
  });

  test('syncs input from URL on initial load', () => {
    render(
      <MemoryRouter initialEntries={['/governance?q=from-url']}>
        <Routes>
          <Route path="/governance" element={<ProposalSearch onSearchChange={vi.fn()} />} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByTestId('proposal-search-input')).toHaveValue('from-url');
  });

  test('clear search removes q from the URL', () => {
    const onSearchChange = vi.fn();
    render(
      <MemoryRouter initialEntries={[`/governance?q=${activeProposalCid}`]}>
        <Routes>
          <Route path="/governance" element={<SearchHarness onSearchChange={onSearchChange} />} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByTestId('url-q').textContent).toBe(activeProposalCid);

    fireEvent.click(screen.getByTestId('proposal-search-clear'));

    expect(screen.getByTestId('proposal-search-input')).toHaveValue('');
    expect(screen.getByTestId('url-q').textContent).toBe('');
    expect(onSearchChange).toHaveBeenLastCalledWith('');
  });
});

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, onTestFinished, test, vi } from 'vitest';
import { renderDappApp } from '../helpers';
import { activeProposalCid } from '../mocks/constants';
import { scanUrl, server, svUrl } from '../setup/setup';

describe('SV UI in dApp mode', () => {
  test('shows governance without a login screen', async () => {
    renderDappApp('/');
    expect(await screen.findByTestId('governance-page-header-title')).toBeInTheDocument();
    expect(screen.queryByText('Log In')).not.toBeInTheDocument();
  });
  test('navigation shows only Governance and no logout', async () => {
    renderDappApp('/');
    await screen.findByTestId('governance-page-header-title');
    const nav = screen.getByRole('navigation');
    expect(within(nav).getByText('Governance')).toBeInTheDocument();
    expect(within(nav).queryByText('Validators')).not.toBeInTheDocument();
    expect(within(nav).queryByText('Global Synchronizer Information')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /log ?out/i })).not.toBeInTheDocument();
  });
  test('redirects operator pages to governance', async () => {
    renderDappApp('/validator-onboarding');
    expect(await screen.findByTestId('governance-page-header-title')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/governance/proposals');
  });
  test('hides Initiate Proposal', async () => {
    renderDappApp('/');
    await screen.findByTestId('governance-page-header-title');
    expect(screen.queryByText('Initiate Proposal')).not.toBeInTheDocument();
  });
  test('never calls the SV app', async () => {
    const svCalls: string[] = [];
    const listener = ({ request }: { request: Request }) => {
      if (request.url.startsWith(svUrl)) svCalls.push(request.url);
    };
    server.events.on('request:start', listener);
    renderDappApp('/');
    await screen.findByTestId('governance-page-header-title');
    server.events.removeListener('request:start', listener);
    expect(svCalls).toEqual([]);
  });
  test('renders governance from Scan', async () => {
    renderDappApp('/');
    const section = await screen.findByTestId('action-required-section');
    expect(await within(section).findAllByTestId('action-required-card')).not.toHaveLength(0);
  });
  test('details page is read-only', async () => {
    renderDappApp(`/governance/proposals/${activeProposalCid}`);
    expect(await screen.findByTestId('proposal-details-your-vote')).toBeInTheDocument();
    expect(screen.getByText('Voting is read-only in this view.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /accept|reject|submit/i })).not.toBeInTheDocument();
  });
  test('redirects the create-proposal page to governance', async () => {
    renderDappApp('/governance/proposals/create');
    expect(await screen.findByTestId('governance-page-header-title')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/governance/proposals');
  });
  test('shows an error instead of a spinner when Scan is unreachable', async () => {
    server.use(http.get(`${scanUrl}/v0/feature-support`, () => HttpResponse.error()));
    // The scan client logs every failed call; keep the expected network errors out of the output.
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    onTestFinished(() => consoleError.mockRestore());
    // Fast-forward through the query retry backoff (~7 s) instead of waiting for it in real time.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderDappApp('/');
    await vi.waitFor(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
      expect(screen.getByText('Cannot reach Scan')).toBeInTheDocument();
    });
    expect(
      screen.getByText(new RegExp(`could not load data from Scan at ${scanUrl}`))
    ).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
    // Poll refetches (1 s interval) must not flip the page back to the spinner.
    await vi.advanceTimersByTimeAsync(3_000);
    expect(screen.getByText('Cannot reach Scan')).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  });
});

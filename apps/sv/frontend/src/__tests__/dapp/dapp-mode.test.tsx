// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, onTestFinished, test, vi } from 'vitest';
import { renderDappApp } from '../helpers';
import { activeProposalCid, svPartyId, voteRequests } from '../mocks/constants';
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
    const section = await screen.findByTestId('inflight-proposals-section-table');
    expect(await within(section).findAllByTestId('inflight-proposals-row')).not.toHaveLength(0);
  });
  test('shows no "you" affordances on the governance page', async () => {
    renderDappApp('/');
    const inflight = await screen.findByTestId('inflight-proposals-section-table');
    expect(await within(inflight).findAllByTestId('inflight-proposals-row')).not.toHaveLength(0);
    expect(screen.queryByTestId('action-required-section')).not.toBeInTheDocument();
    expect(screen.queryByText('Action Required')).not.toBeInTheDocument();
    expect(screen.queryByText('YOUR VOTE')).not.toBeInTheDocument();
    expect(screen.queryByTestId('inflight-proposals-row-your-vote')).not.toBeInTheDocument();
    expect(document.getElementById('nav-badge-governance-count')).toBeNull();
  });
  test('details page is read-only', async () => {
    renderDappApp(`/governance/proposals/${activeProposalCid}`);
    expect(await screen.findByTestId('proposal-details-your-vote')).toBeInTheDocument();
    expect(screen.getByText('Voting is read-only in this view.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /accept|reject|submit/i })).not.toBeInTheDocument();
    expect(screen.getAllByTestId('proposal-details-vote')).not.toHaveLength(0);
    expect(screen.queryByTestId('proposal-details-voter-party-id-badge')).not.toBeInTheDocument();
  });
  test('details page marks no vote as yours', async () => {
    // A vote by the SV whose identity the mocked DSO info carries: in dApp mode it is not the viewer.
    const activeRequest = voteRequests.dso_rules_vote_requests.find(
      vr => vr.contract_id === activeProposalCid
    )!;
    const withSvVote = {
      ...activeRequest,
      payload: {
        ...(activeRequest.payload as object),
        votes: [
          ['Digital-Asset-2', { sv: svPartyId, accept: true, reason: { url: '', body: 'mine?' } }],
        ],
      },
    };
    server.use(
      http.get(`${scanUrl}/v0/voterequests/:id`, () =>
        HttpResponse.json({ dso_rules_vote_request: withSvVote })
      )
    );
    renderDappApp(`/governance/proposals/${activeProposalCid}`);
    expect(await screen.findByText('mine?')).toBeInTheDocument();
    const voters = screen
      .getAllByTestId('proposal-details-voter-party-id-value')
      .map(e => e.textContent);
    expect(voters).toContain(svPartyId);
    expect(screen.queryByTestId('proposal-details-voter-party-id-badge')).not.toBeInTheDocument();
    expect(screen.queryByTestId('your-vote-edit-button')).not.toBeInTheDocument();
    expect(screen.queryByTestId('your-vote-form')).not.toBeInTheDocument();
    expect(screen.getByText('Voting is read-only in this view.')).toBeInTheDocument();
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
  test('keeps the UI when Scan fails after a successful load', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    onTestFinished(() => consoleError.mockRestore());
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderDappApp('/');
    await screen.findByTestId('governance-page-header-title');

    let failedCalls = 0;
    server.use(
      http.get(`${scanUrl}/v0/feature-support`, () => {
        failedCalls++;
        return new HttpResponse(null, { status: 500 });
      })
    );
    // Past the next poll (1 s) and its 3 retries (~7 s backoff), so the query ends in error.
    await vi.waitFor(
      async () => {
        await vi.advanceTimersByTimeAsync(1_000);
        expect(failedCalls).toBeGreaterThanOrEqual(4);
      },
      { timeout: 10_000 }
    );
    await vi.advanceTimersByTimeAsync(1_000);

    expect(screen.queryByText('Cannot reach Scan')).not.toBeInTheDocument();
    expect(screen.getByTestId('governance-page-header-title')).toBeInTheDocument();
    expect(within(screen.getByRole('navigation')).getByText('Governance')).toBeInTheDocument();
  });
});

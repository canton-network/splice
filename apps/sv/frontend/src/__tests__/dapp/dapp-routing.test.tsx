// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test } from 'vitest';
import App from '../../App';
import { SvConfigProvider } from '../../utils';
import { renderDappApp } from '../helpers';

// '/validator-onboarding' and '/governance/proposals/create' are covered in dapp-mode.test.tsx.
describe('SV UI routing in dApp mode', () => {
  test.each([
    '/',
    '/dso',
    '/amulet-price',
    '/delegate-election',
    '/votes',
    '/governance',
    '/no-such-page',
  ])('redirects %s to the governance proposals page', async path => {
    renderDappApp(path);
    expect(await screen.findByTestId('governance-page-header-title')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/governance/proposals');
  });
});

// The route tree is shared with dApp mode; check that the dApp-only redirects do not leak into operator mode.
describe('SV UI routing in operator mode', () => {
  const renderOperatorApp = (path: string) => {
    window.history.pushState({}, '', path);
    render(
      <SvConfigProvider>
        <App />
      </SvConfigProvider>
    );
  };

  test('shows the router error page for an unknown path', async () => {
    renderOperatorApp('/no-such-page');
    expect(await screen.findByText('Something went wrong!')).toBeInTheDocument();
    expect(screen.getByText('Not Found')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/no-such-page');
  });

  test('renders the create-proposal page', async () => {
    const user = userEvent.setup();
    renderOperatorApp('/governance/proposals/create');
    // Log in with the test auth (as in sv.test.tsx); the login form replaces the page in place.
    expect(await screen.findByText('Log In')).toBeInTheDocument();
    await user.type(screen.getByRole('textbox'), 'sv1');
    await user.click(screen.getByRole('button', { name: 'Log In' }));
    expect(await screen.findByTestId('initiate-proposal-layout')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/governance/proposals/create');
  });
});

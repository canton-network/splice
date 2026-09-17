// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, test, vi } from 'vitest';

import SvTopNav from '../../components/layout/SvTopNav';

const navLinks = [
  { name: 'Global Synchronizer Information', path: '/dso' },
  { name: 'Governance', path: '/governance' },
  { name: 'Teluma Price', path: '/amulet-price' },
  { name: 'Validators', path: '/validator-onboarding' },
];

describe('SvTopNav', () => {
  test('renders brand, centered nav cluster, and logout', () => {
    render(
      <MemoryRouter>
        <SvTopNav navLinks={navLinks} onLogout={vi.fn()} />
      </MemoryRouter>
    );

    expect(screen.getByTestId('app-title')).toHaveTextContent('Supervalidator Operations');
    expect(screen.getByTestId('sv-top-nav-links')).toBeInTheDocument();
    expect(screen.getByTestId('navlink-dso')).toBeInTheDocument();
    expect(screen.getByTestId('navlink-governance')).toBeInTheDocument();
    expect(screen.getByTestId('logout-button')).toBeInTheDocument();

    const row = screen.getByTestId('sv-top-nav');
    expect(row).toHaveStyle({ display: 'flex' });
    expect(screen.getByTestId('sv-top-nav-spacer-start')).toBeInTheDocument();
    expect(screen.getByTestId('sv-top-nav-spacer-end')).toBeInTheDocument();
  });
});

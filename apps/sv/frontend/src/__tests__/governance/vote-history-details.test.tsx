// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen, within } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import { SvConfigProvider } from '../../utils';
import App from '../../App';
import { navigateToGovernancePage } from '../helpers';

type UserEvent = ReturnType<typeof userEvent.setup>;

const GovernanceWithConfig = () => {
  return (
    <SvConfigProvider>
      <App />
    </SvConfigProvider>
  );
};

async function login(user: UserEvent) {
  render(<GovernanceWithConfig />);

  expect(await screen.findByText('Log In')).toBeInTheDocument();

  const input = screen.getByRole('textbox');
  await user.type(input, 'sv1');

  const button = screen.queryByRole('button', { name: 'Log In' });
  if (button) {
    await user.click(button);
  }
}

async function openVoteHistoryDetails(user: UserEvent, effectiveAtCell: string) {
  await login(user);
  await navigateToGovernancePage(user);

  const rows = await screen.findAllByTestId('vote-history-row');
  const row = rows.find(r =>
    within(r).queryByText(effectiveAtCell, { selector: '[data-testid*=vote-takes-effect] *' })
  );
  expect(row).toBeDefined();

  await user.click(row!);

  expect(await screen.findByTestId('proposal-details-title')).toBeInTheDocument();
}

describe('Vote history proposal details', () => {
  // Dates below mirror the first two entries of voteResultsAmuletRules in mocks/constants.ts,
  // whose request payloads have no targetEffectiveAt (like pre-effectivity vote requests).
  test('accepted vote shows the outcome effective date, not Threshold', async () => {
    const user = userEvent.setup();
    const effectiveAt = dayjs('2024-04-20T08:30:00Z').format(dateTimeFormatISO);

    await openVoteHistoryDetails(user, effectiveAt);

    const voteTakesEffect = await screen.findByTestId('proposal-details-vote-takes-effect-value');
    expect(voteTakesEffect.textContent).toBe(effectiveAt);
  });

  test('rejected vote shows the completion date, not Threshold', async () => {
    const user = userEvent.setup();
    const completedAt = dayjs('2024-04-20T08:21:26.130819Z').format(dateTimeFormatISO);

    await openVoteHistoryDetails(user, completedAt);

    const voteTakesEffect = await screen.findByTestId('proposal-details-vote-takes-effect-value');
    expect(voteTakesEffect.textContent).toBe(completedAt);
  });
});

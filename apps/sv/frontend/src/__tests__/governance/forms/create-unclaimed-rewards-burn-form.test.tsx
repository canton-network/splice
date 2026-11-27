// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, test } from 'vitest';
import App from '../../../App';
import { SvConfigProvider } from '../../../utils';
import { svPartyId } from '../../mocks/constants';
import { CreateUnclaimedRewardsBurnForm } from '../../../components/forms/CreateUnclaimedRewardsBurnForm';
import { Wrapper } from '../../helpers';
import { server, svUrl } from '../../setup/setup';

const renderForm = () =>
  render(
    <Wrapper>
      <CreateUnclaimedRewardsBurnForm />
    </Wrapper>
  );

describe('Burn Unclaimed Rewards Form', () => {
  test('renders the amount and common proposal fields without a beneficiary or mint deadline', () => {
    renderForm();

    expect(screen.getByTestId('create-unclaimed-rewards-burn-form')).toBeInTheDocument();
    expect(screen.getByTestId('create-unclaimed-rewards-burn-action')).toHaveTextContent(
      'Burn Unclaimed Rewards'
    );
    for (const field of ['amount', 'summary', 'url', 'expiry-date-field', 'effective-date-field']) {
      expect(screen.getByTestId(`create-unclaimed-rewards-burn-${field}`)).toBeInTheDocument();
    }
    expect(screen.queryByText('Beneficiary')).not.toBeInTheDocument();
    expect(screen.queryByText('Must Mint Before')).not.toBeInTheDocument();
  });

  test.each([
    ['', 'Amount is required'],
    ['0', 'Amount must be greater than zero'],
    ['0.0000000000', 'Amount must be greater than zero'],
    ['-1', 'Amount must be a valid number'],
    ['abc', 'Amount must be a valid number'],
    ['1.12345678901', 'Amount can have at most 10 decimal places'],
  ])('rejects invalid amount %j', async (amount, message) => {
    const user = userEvent.setup();
    renderForm();

    const input = screen.getByTestId('create-unclaimed-rewards-burn-amount');
    if (amount) {
      await user.type(input, amount);
      await user.tab();
    } else {
      await user.click(screen.getByTestId('submit-button'));
    }

    expect(screen.getByTestId('create-unclaimed-rewards-burn-amount-error')).toHaveTextContent(
      message
    );
    expect(screen.queryByTestId('proposal-review')).not.toBeInTheDocument();
  });

  test.each(['100', '0.0000000001', '100.1234567890'])(
    'accepts positive amount %s',
    async amount => {
      const user = userEvent.setup();
      renderForm();
      await user.type(screen.getByTestId('create-unclaimed-rewards-burn-amount'), amount);
      await user.tab();
      expect(
        screen.getByTestId('create-unclaimed-rewards-burn-amount-error')
      ).toBeEmptyDOMElement();
    }
  );

  test('reviews the permanent burn warning and submits the amount with summary as reason', async () => {
    let requestBody: unknown;
    server.use(
      http.post(`${svUrl}/v0/admin/sv/voterequest/create`, async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({});
      })
    );
    const user = userEvent.setup();
    const login = render(
      <SvConfigProvider>
        <App />
      </SvConfigProvider>
    );
    await user.type(await screen.findByRole('textbox'), 'sv1');
    await user.click(screen.getByRole('button', { name: 'Log In' }));
    expect(await screen.findAllByDisplayValue(svPartyId)).not.toHaveLength(0);
    login.unmount();
    renderForm();

    await user.type(
      screen.getByTestId('create-unclaimed-rewards-burn-summary'),
      'Missed milestone'
    );
    await user.type(screen.getByTestId('create-unclaimed-rewards-burn-url'), 'https://example.com');
    await user.type(screen.getByTestId('create-unclaimed-rewards-burn-amount'), '100.1234567890');
    await user.click(screen.getByTestId('create-unclaimed-rewards-burn-action'));
    const submitButton = screen.getByTestId('submit-button');
    await waitFor(() => expect(submitButton).toBeEnabled());
    await user.click(submitButton);

    expect(screen.getByTestId('proposal-review')).toBeInTheDocument();
    expect(screen.getByTestId('unclaimed-rewards-burn-warning')).toHaveTextContent(
      'If this proposal is accepted, this amount will be permanently burned from the unclaimed rewards pool.'
    );
    expect(screen.getByText('100.1234567890')).toBeInTheDocument();
    expect(requestBody).toBeUndefined();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Submit Proposal' })).toBeEnabled()
    );
    await user.click(screen.getByRole('button', { name: 'Submit Proposal' }));

    await waitFor(() =>
      expect(requestBody).toEqual(
        expect.objectContaining({
          action: {
            tag: 'ARC_DsoRules',
            value: {
              dsoAction: {
                tag: 'SRARC_CreateUnclaimedRewardsBurnInstruction',
                value: { amount: '100.1234567890', reason: 'Missed milestone' },
              },
            },
          },
        })
      )
    );
    expect(await screen.findByText('Successfully submitted the proposal')).toBeInTheDocument();
  });
});

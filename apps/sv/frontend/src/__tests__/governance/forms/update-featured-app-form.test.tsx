// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { SvConfigProvider } from '../../../utils';
import App from '../../../App';
import { svPartyId } from '../../mocks/constants';
import { Wrapper } from '../../helpers';
import { UpdateFeaturedAppForm } from '../../../components/forms/UpdateFeaturedAppForm';
import { server, svUrl } from '../../setup/setup';
import { http, HttpResponse } from 'msw';
import { PROPOSAL_SUMMARY_SUBTITLE, PROPOSAL_SUMMARY_TITLE } from '../../../utils/constants';

// Logs in once so the admin client has an access token for the rest of the file.
describe('SV user can', () => {
  test('login and see the SV party ID', async () => {
    const user = userEvent.setup();
    render(
      <SvConfigProvider>
        <App />
      </SvConfigProvider>
    );

    expect(await screen.findByText('Log In')).toBeInTheDocument();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    const button = screen.getByRole('button', { name: 'Log In' });
    await user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).not.toBe([]);
  });
});

describe('Update Featured App Form', () => {
  const fillOutUpdateForm = async (user: ReturnType<typeof userEvent.setup>) => {
    const summaryInput = screen.getByTestId('update-featured-app-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('update-featured-app-url');
    await user.type(urlInput, 'https://example.com');

    const partyIdInput = screen.getByTestId('update-featured-app-partyId');
    await user.type(partyIdInput, 'a-party-id::1014912492');
    fireEvent.blur(partyIdInput);

    await waitFor(() => {
      expect(screen.queryByText('Loading app rights...')).not.toBeInTheDocument();
    });

    const rightCidDropdown = screen.getByTestId('update-featured-app-rightCid-dropdown');
    await waitFor(
      () => {
        expect(rightCidDropdown).not.toBeDisabled();
      },
      { timeout: 3000 }
    );
    fireEvent.change(rightCidDropdown, { target: { value: 'rightCid123' } });
    fireEvent.blur(rightCidDropdown);
    await waitFor(() => {
      expect(screen.getByTestId('update-featured-app-rightCid-error').textContent).toBeFalsy();
    });

    const activityWeightInput = screen.getByTestId('update-featured-app-activityWeight');
    await user.type(activityWeightInput, '2.5');

    const reasonInput = screen.getByTestId('update-featured-app-reason');
    await user.type(reasonInput, 'test');
  };

  test('should render all Form components', () => {
    render(
      <Wrapper>
        <UpdateFeaturedAppForm />
      </Wrapper>
    );

    expect(screen.getByTestId('update-featured-app-form')).toBeInTheDocument();
    expect(screen.getByText('Proposal type')).toBeInTheDocument();

    const actionInput = screen.getByTestId('update-featured-app-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.textContent).toBe('Update Featured Application');

    const summaryInput = screen.getByTestId('update-featured-app-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const summarySubtitle = screen.getByTestId('update-featured-app-summary-subtitle');
    expect(summarySubtitle).toBeInTheDocument();
    expect(summarySubtitle.textContent).toBe(PROPOSAL_SUMMARY_SUBTITLE);

    const urlInput = screen.getByTestId('update-featured-app-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');

    const partyIdInput = screen.getByTestId('update-featured-app-partyId');
    expect(partyIdInput).toBeInTheDocument();
    expect(partyIdInput.getAttribute('value')).toBe('');

    const partyIdTitle = screen.getByTestId('update-featured-app-partyId-title');
    expect(partyIdTitle).toBeInTheDocument();
    expect(partyIdTitle.textContent).toBe('Provider Party ID');

    const rightCidDropdown = screen.getByTestId('update-featured-app-rightCid-dropdown');
    expect(rightCidDropdown).toBeInTheDocument();
    expect(rightCidDropdown).toBeDisabled();

    expect(screen.getByTestId('update-featured-app-activityWeight')).toBeInTheDocument();
    expect(screen.getByTestId('update-featured-app-reason')).toBeInTheDocument();

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
  });

  test('activity weight is required', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UpdateFeaturedAppForm />
      </Wrapper>
    );

    const activityWeightInput = screen.getByTestId('update-featured-app-activityWeight');
    const actionInput = screen.getByTestId('update-featured-app-action');

    await user.click(activityWeightInput);
    await user.click(actionInput); // blur to trigger validation

    await waitFor(() => {
      expect(screen.getByTestId('update-featured-app-activityWeight-error').textContent).toBe(
        'Weight is required'
      );
    });
  });

  test('should send new activity weight and reason to backend', async () => {
    let requestBody = '';
    server.use(
      http.post(`${svUrl}/v0/admin/sv/voterequest/create`, async ({ request }) => {
        requestBody = await request.text();
        return HttpResponse.json({});
      })
    );

    const user = userEvent.setup();

    render(
      <Wrapper>
        <UpdateFeaturedAppForm />
      </Wrapper>
    );

    await fillOutUpdateForm(user);

    const actionInput = screen.getByTestId('update-featured-app-action');
    await user.click(actionInput); // blur to trigger validation

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(() => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton); // review proposal
    await user.click(submitButton); // submit proposal

    await waitFor(() => {
      expect(requestBody).toContain('"newActivityWeight":"2.5"');
      expect(requestBody).toContain('"reason":"test"');
    });
  });

  test('activity weight rejects negative numbers and more than 10 decimal places', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UpdateFeaturedAppForm />
      </Wrapper>
    );

    const activityWeightInput = screen.getByTestId('update-featured-app-activityWeight');
    const activityWeightError = screen.getByTestId('update-featured-app-activityWeight-error');

    await user.type(activityWeightInput, '-1');
    await waitFor(() => {
      expect(activityWeightError.textContent).toBe('Weight must be a valid non-negative number');
    });

    await user.clear(activityWeightInput);
    await user.type(activityWeightInput, '1.1234567891');
    await waitFor(() => {
      expect(activityWeightError.textContent).toBe('');
    });

    await user.clear(activityWeightInput);
    await user.type(activityWeightInput, '1.12345678912');
    await waitFor(() => {
      expect(activityWeightError.textContent).toBe('Weight can have at most 10 decimal places');
    });
  });

  test('reason is required', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UpdateFeaturedAppForm />
      </Wrapper>
    );

    const reasonInput = screen.getByTestId('update-featured-app-reason');
    const actionInput = screen.getByTestId('update-featured-app-action');

    await user.click(reasonInput);
    await user.click(actionInput); // blur to trigger validation

    await waitFor(() => {
      expect(screen.getByTestId('update-featured-app-reason-error').textContent).toBe(
        'Reason is required'
      );
    });
  });

  test('communicates when the provider has no featured app rights to update', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UpdateFeaturedAppForm />
      </Wrapper>
    );

    const partyIdInput = screen.getByTestId('update-featured-app-partyId');
    await user.type(partyIdInput, 'no-rights-party::1014912492');
    fireEvent.blur(partyIdInput);

    await waitFor(() => {
      expect(screen.getByTestId('update-featured-app-rightCid')).toHaveTextContent(
        'No featured application rights found for this provider'
      );
    });

    expect(screen.getByTestId('update-featured-app-rightCid-dropdown')).toBeDisabled();
  });

  test('expiry date must be in the future', async () => {
    render(
      <Wrapper>
        <UpdateFeaturedAppForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('update-featured-app-expiry-date-field');
    expect(expiryDateInput).toBeInTheDocument();

    const thePast = dayjs().subtract(1, 'day').format(dateTimeFormatISO);
    const theFuture = dayjs().add(1, 'day').format(dateTimeFormatISO);

    fireEvent.change(expiryDateInput, { target: { value: thePast } });

    await waitFor(() => {
      expect(screen.queryByText('Expiration must be in the future')).toBeInTheDocument();
    });

    fireEvent.change(expiryDateInput, { target: { value: theFuture } });

    await waitFor(() => {
      expect(screen.queryByText('Expiration must be in the future')).not.toBeInTheDocument();
    });
  });

  test('effective date must be after expiry date', async () => {
    render(
      <Wrapper>
        <UpdateFeaturedAppForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('update-featured-app-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('update-featured-app-effective-date-field');

    const expiryDate = dayjs().add(1, 'week');
    const effectiveDate = expiryDate.subtract(1, 'day');

    fireEvent.change(expiryDateInput, { target: { value: expiryDate.format(dateTimeFormatISO) } });
    fireEvent.change(effectiveDateInput, {
      target: { value: effectiveDate.format(dateTimeFormatISO) },
    });

    await waitFor(() => {
      expect(
        screen.queryByText('Effective Date must be after expiration date')
      ).toBeInTheDocument();
    });

    const validEffectiveDate = expiryDate.add(1, 'day').format(dateTimeFormatISO);

    fireEvent.change(effectiveDateInput, { target: { value: validEffectiveDate } });

    await waitFor(() => {
      expect(
        screen.queryByText('Effective Date must be after expiration date')
      ).not.toBeInTheDocument();
    });
  });

  test('should show proposal review page after form completion', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UpdateFeaturedAppForm />
      </Wrapper>
    );

    await fillOutUpdateForm(user);

    const actionInput = screen.getByTestId('update-featured-app-action');
    await user.click(actionInput); // blur to trigger validation

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(() => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton); // review proposal

    expect(screen.getByText(PROPOSAL_SUMMARY_TITLE)).toBeInTheDocument();
    expect(screen.getByTestId('updateProviderPartyId-field').textContent).toBe(
      'a-party-id::1014912492'
    );
    expect(screen.getByTestId('updateRight-field').textContent).toBe('rightCid123');
    expect(screen.getByTestId('updateActivityWeight-field').textContent).toBe('2.5');
    expect(screen.getByTestId('updateReason-field').textContent).toBe('test');
  });
});

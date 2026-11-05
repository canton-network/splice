// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { RegisterSynchronizerForm } from '../../../components/forms/RegisterSynchronizerForm';
import { Wrapper } from '../../helpers';
import {
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  PROPOSAL_SUMMARY_SUBTITLE,
} from '../../../utils/constants';

// The lookup needs an authenticated SV admin client, which the render wrapper does not set
// up, so the two sources the check reads are mocked and the component's own logic tested.
const mockListRegistrations = vi.fn();
const mockCreateVoteRequest = vi.fn();
const mockVoteRequests = vi.fn();
vi.mock('../../../contexts/SvAdminServiceContext', async importOriginal => ({
  ...(await importOriginal<typeof import('../../../contexts/SvAdminServiceContext')>()),
  useSvAdminClient: () => ({
    listSynchronizerRegistrations: mockListRegistrations,
    createVoteRequest: mockCreateVoteRequest,
  }),
}));
vi.mock('../../../hooks/useListVoteRequests', () => ({
  useListDsoRulesVoteRequests: () => mockVoteRequests(),
}));

const validSynchronizerId = 'dedicated::1220deadbeef';
const validPaymaster = 'paymaster::1220feedface';
const weightFields = ['maxActivityWeight', 'maxAssetActivityWeight'] as const;

beforeEach(() => {
  vi.clearAllMocks();
  mockListRegistrations.mockResolvedValue({ registrations: [] });
  mockCreateVoteRequest.mockResolvedValue(undefined);
  mockVoteRequests.mockReturnValue({ data: [] });
});

describe('Register Dedicated Synchronizer Form', () => {
  test('should render all Register Dedicated Synchronizer Form components', () => {
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    expect(screen.getByTestId('register-synchronizer-form')).toBeInTheDocument();
    expect(screen.getByText(CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE)).toBeInTheDocument();

    const actionInput = screen.getByTestId('register-synchronizer-action');
    expect(actionInput.textContent).toBe('Register Dedicated Synchronizer');

    expect(screen.getByTestId('register-synchronizer-synchronizer-id')).toBeInTheDocument();
    expect(screen.getByTestId('register-synchronizer-paymaster')).toBeInTheDocument();
    expect(screen.getByTestId('register-synchronizer-maxActivityWeight')).toBeInTheDocument();
    expect(screen.getByTestId('register-synchronizer-maxAssetActivityWeight')).toBeInTheDocument();

    const summarySubtitle = screen.getByTestId('register-synchronizer-summary-subtitle');
    expect(summarySubtitle.textContent).toBe(PROPOSAL_SUMMARY_SUBTITLE);
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    const submitButton = screen.getByTestId('submit-button');
    await user.click(submitButton);
    expect(submitButton.getAttribute('disabled')).not.toBeNull();

    screen.getByText('Summary is required');
    screen.getByText('Invalid URL');

    // completing the form should reenable the submit button
    await user.type(screen.getByTestId('register-synchronizer-summary'), 'Register a sync');
    await user.type(screen.getByTestId('register-synchronizer-url'), 'https://example.com');
    await user.type(
      screen.getByTestId('register-synchronizer-synchronizer-id'),
      validSynchronizerId
    );
    await user.type(screen.getByTestId('register-synchronizer-paymaster'), validPaymaster);

    // the weights are prefilled, so they never block submission
    await user.click(screen.getByTestId('register-synchronizer-action'));

    expect(submitButton.getAttribute('disabled')).toBeNull();
  });

  test('rejects a synchronizer id that is not name::fingerprint', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    const synchronizerIdInput = screen.getByTestId('register-synchronizer-synchronizer-id');
    await user.type(synchronizerIdInput, 'not-a-synchronizer-id');
    await user.click(screen.getByTestId('register-synchronizer-action'));

    screen.getByText('Invalid synchronizer id. Expected format: name::fingerprint');
  });

  test('rejects a synchronizer id that Canton cannot parse', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    const synchronizerIdInput = screen.getByTestId('register-synchronizer-synchronizer-id');
    await user.type(synchronizerIdInput, `dedicated::${'1'.repeat(69)}`);
    await user.click(screen.getByTestId('register-synchronizer-action'));

    screen.getByText('Invalid synchronizer id: the fingerprint has more than 68 characters');
    expect(mockListRegistrations).not.toHaveBeenCalled();
  });

  test('prefills both weights with the full weight', () => {
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    for (const key of weightFields) {
      expect(screen.getByTestId(`register-synchronizer-${key}`).getAttribute('value')).toBe('1.0');
    }
  });

  test.each(weightFields)('rejects a negative %s and more than 10 decimal places', async key => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    const input = screen.getByTestId(`register-synchronizer-${key}`);

    await user.clear(input);
    await user.type(input, '-1');
    await user.click(screen.getByTestId('register-synchronizer-action'));
    screen.getByText('Weight must be a valid non-negative number');

    await user.clear(input);
    await user.type(input, '0.12345678901');
    await user.click(screen.getByTestId('register-synchronizer-action'));
    screen.getByText('Weight can have at most 10 decimal places');

    // The template only requires a weight of at least 0, so 0 and weights above 1 are valid.
    for (const weight of ['0', '2.0']) {
      await user.clear(input);
      await user.type(input, weight);
      await user.click(screen.getByTestId('register-synchronizer-action'));
      expect(screen.getByTestId(`register-synchronizer-${key}-error`).textContent).toBe('');
    }
  });

  test('rejects a paymaster that is not a party id', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    await user.type(screen.getByTestId('register-synchronizer-paymaster'), 'not-a-party');
    await user.click(screen.getByTestId('register-synchronizer-action'));

    expect(await screen.findByText(/Invalid PartyId format/)).toBeInTheDocument();
  });

  test('proposes the registration with the paymaster and both weights', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    await user.type(screen.getByTestId('register-synchronizer-summary'), 'Register a sync');
    await user.type(screen.getByTestId('register-synchronizer-url'), 'https://example.com');
    await user.type(
      screen.getByTestId('register-synchronizer-synchronizer-id'),
      validSynchronizerId
    );
    await user.type(screen.getByTestId('register-synchronizer-paymaster'), validPaymaster);
    const assetWeight = screen.getByTestId('register-synchronizer-maxAssetActivityWeight');
    await user.clear(assetWeight);
    await user.type(assetWeight, '0.25');
    await user.click(screen.getByTestId('register-synchronizer-action'));

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(() => expect(submitButton.getAttribute('disabled')).toBeNull());
    await user.click(submitButton); // review proposal

    await screen.findByTestId('proposal-review');
    expect(screen.getByTestId('synchronizerId-field').textContent).toBe(validSynchronizerId);
    expect(screen.getByTestId('maxActivityWeight-field').textContent).toBe('1.0');
    expect(screen.getByTestId('maxAssetActivityWeight-field').textContent).toBe('0.25');

    await user.click(submitButton); // submit proposal

    await waitFor(() => expect(mockCreateVoteRequest).toHaveBeenCalledTimes(1));
    expect(mockCreateVoteRequest.mock.calls[0][1]).toEqual({
      tag: 'ARC_DsoRules',
      value: {
        dsoAction: {
          tag: 'SRARC_RegisterSynchronizer',
          value: {
            synchronizerId: validSynchronizerId,
            paymaster: validPaymaster,
            maxActivityWeight: '1.0',
            maxAssetActivityWeight: '0.25',
          },
        },
      },
    });
  });
});

describe('Register Dedicated Synchronizer Form, duplicate registration', () => {
  // Blurring runs the check without waiting out the typing debounce.
  const enterSynchronizerId = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.type(
      screen.getByTestId('register-synchronizer-synchronizer-id'),
      validSynchronizerId
    );
    await user.click(screen.getByTestId('register-synchronizer-paymaster'));
  };

  test('rejects a synchronizer id that is already registered', async () => {
    mockListRegistrations.mockResolvedValue({ registrations: [{}] });
    const user = userEvent.setup();
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    await enterSynchronizerId(user);

    expect(await screen.findByText(/already registered/)).toBeInTheDocument();
    expect(mockListRegistrations).toHaveBeenCalledWith(validSynchronizerId);
  });

  test('rejects an id another open proposal already asks to register', async () => {
    mockVoteRequests.mockReturnValue({
      data: [
        {
          payload: {
            action: {
              tag: 'ARC_DsoRules',
              value: {
                dsoAction: {
                  tag: 'SRARC_RegisterSynchronizer',
                  value: { synchronizerId: validSynchronizerId },
                },
              },
            },
          },
        },
      ],
    });
    const user = userEvent.setup();
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    await enterSynchronizerId(user);

    expect(await screen.findByText(/Another open proposal/)).toBeInTheDocument();
    // an id already spoken for on the proposal side is not worth a round trip
    expect(mockListRegistrations).not.toHaveBeenCalled();
  });

  test('accepts an id that is neither registered nor proposed', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    await enterSynchronizerId(user);

    await waitFor(() => expect(mockListRegistrations).toHaveBeenCalledWith(validSynchronizerId));
    expect(screen.queryByText(/already registered/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Another open proposal/)).not.toBeInTheDocument();
  });

  test('does not look up an id that is not yet well formed', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    await user.type(screen.getByTestId('register-synchronizer-synchronizer-id'), 'not-an-id');
    await user.click(screen.getByTestId('register-synchronizer-paymaster'));

    // the check is gated on the id parsing, so the ledger is never asked about a malformed one
    expect(await screen.findByText(/Invalid synchronizer id/)).toBeInTheDocument();
    expect(mockListRegistrations).not.toHaveBeenCalled();
  });

  test('a failing lookup does not block the proposal', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    mockListRegistrations.mockRejectedValue(new Error('the SV app is unreachable'));
    const user = userEvent.setup();
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    await enterSynchronizerId(user);

    await waitFor(() => expect(mockListRegistrations).toHaveBeenCalledWith(validSynchronizerId));
    expect(screen.queryByText(/already registered/)).not.toBeInTheDocument();
  });

  test('keeps an otherwise complete proposal unsubmittable while the id is a duplicate', async () => {
    mockListRegistrations.mockResolvedValue({ registrations: [{}] });
    const user = userEvent.setup();
    render(
      <Wrapper>
        <RegisterSynchronizerForm />
      </Wrapper>
    );

    await user.type(screen.getByTestId('register-synchronizer-summary'), 'Register a sync');
    await user.type(screen.getByTestId('register-synchronizer-url'), 'https://example.com');
    await user.type(screen.getByTestId('register-synchronizer-paymaster'), validPaymaster);
    await enterSynchronizerId(user);

    expect(await screen.findByText(/already registered/)).toBeInTheDocument();
    expect(screen.getByTestId('submit-button').getAttribute('disabled')).not.toBeNull();
  });
});

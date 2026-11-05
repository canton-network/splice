// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { UpdateSynchronizerRegistrationForm } from '../../../components/forms/UpdateSynchronizerRegistrationForm';
import { Wrapper } from '../../helpers';
import {
  activeSynchronizerRegistration,
  activeSynchronizerRegistrationPayload,
} from '../../mocks/constants';

// The lookup needs an authenticated SV admin client, which the render wrapper does not set
// up, so the sources the form reads are mocked and the component's own logic tested.
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

const synchronizerId = activeSynchronizerRegistrationPayload.synchronizerId;
const registrationCid = activeSynchronizerRegistration.contract_id;
const prefix = 'update-synchronizer-registration';
const weightInput = `${prefix}-maxActivityWeight`;

const duplicateRegistration = {
  ...activeSynchronizerRegistration,
  contract_id: registrationCid.replace(/^00a1/, '00b2'),
  payload: {
    ...activeSynchronizerRegistrationPayload,
    maxActivityWeight: '0.6000000000',
  },
};
const duplicateCid = duplicateRegistration.contract_id;

beforeEach(() => {
  vi.clearAllMocks();
  mockListRegistrations.mockResolvedValue({ registrations: [activeSynchronizerRegistration] });
  mockCreateVoteRequest.mockResolvedValue(undefined);
  mockVoteRequests.mockReturnValue({ data: [] });
});

const renderForm = () =>
  render(
    <Wrapper>
      <UpdateSynchronizerRegistrationForm />
    </Wrapper>
  );

// Blurring runs the lookup without waiting out the typing debounce.
const enterSynchronizerId = async (user: ReturnType<typeof userEvent.setup>, value: string) => {
  await user.type(screen.getByTestId(`${prefix}-synchronizer-id`), value);
  await user.click(screen.getByTestId(`${prefix}-action`));
};

describe('Update Dedicated Synchronizer Registration Form', () => {
  test('renders the form without the registration fields until a registration is found', () => {
    renderForm();

    expect(screen.getByTestId(`${prefix}-form`)).toBeInTheDocument();
    expect(screen.getByTestId(`${prefix}-action`).textContent).toBe(
      'Update Dedicated Synchronizer Registration'
    );
    expect(screen.getByTestId(`${prefix}-synchronizer-id`)).toBeInTheDocument();
    expect(screen.queryByTestId(weightInput)).not.toBeInTheDocument();
  });

  test('shows the registration and prefills its current values', async () => {
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, synchronizerId);

    await screen.findByTestId(`${prefix}-resolved`);
    expect(mockListRegistrations).toHaveBeenCalledWith(synchronizerId);
    expect(screen.getByTestId(`${prefix}-resolved-contract-id-field`).textContent).toBe(
      registrationCid
    );
    expect(
      screen.getByTestId(`${prefix}-resolved-current-maxActivityWeight-field`).textContent
    ).toBe('1.0');
    await waitFor(() => expect(screen.getByTestId(weightInput).getAttribute('value')).toBe('1.0'));
    expect(screen.getByTestId(`${prefix}-maxAssetActivityWeight`).getAttribute('value')).toBe(
      '0.5'
    );
    expect(screen.getByTestId(`${prefix}-paymaster`).getAttribute('value')).toBe(
      activeSynchronizerRegistrationPayload.paymaster
    );
  });

  test('picks the only registration without a duplicates warning', async () => {
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, synchronizerId);

    await screen.findByTestId(`${prefix}-resolved`);
    expect(screen.getByTestId(`${prefix}-contract-id`)).toHaveTextContent(registrationCid);
    expect(screen.queryByTestId(`${prefix}-duplicates-warning`)).not.toBeInTheDocument();
  });

  test('warns about duplicate registrations and lets the proposer pick one', async () => {
    mockListRegistrations.mockResolvedValue({
      registrations: [activeSynchronizerRegistration, duplicateRegistration],
    });
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, synchronizerId);

    expect(await screen.findByTestId(`${prefix}-duplicates-warning`)).toHaveTextContent(
      /2 registrations/
    );
    expect(screen.queryByTestId(`${prefix}-resolved`)).not.toBeInTheDocument();

    const dropdown = screen.getByTestId(`${prefix}-contract-id-dropdown`);
    fireEvent.change(dropdown, { target: { value: duplicateCid } });
    fireEvent.blur(dropdown);

    await screen.findByTestId(`${prefix}-resolved`);
    expect(screen.getByTestId(`${prefix}-resolved-contract-id-field`).textContent).toBe(
      duplicateCid
    );
    await waitFor(() => expect(screen.getByTestId(weightInput).getAttribute('value')).toBe('0.6'));
  });

  test('rejects a synchronizer id that is not registered', async () => {
    mockListRegistrations.mockResolvedValue({ registrations: [] });
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, synchronizerId);

    expect(await screen.findByText(/No registration found/)).toBeInTheDocument();
    expect(screen.queryByTestId(weightInput)).not.toBeInTheDocument();
  });

  test('does not look up a malformed synchronizer id', async () => {
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, 'not-an-id');

    expect(await screen.findByText(/Invalid synchronizer id/)).toBeInTheDocument();
    expect(mockListRegistrations).not.toHaveBeenCalled();
  });

  test('a failing lookup blocks the proposal, since there is no contract to target', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    mockListRegistrations.mockRejectedValue(new Error('the SV app is unreachable'));
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, synchronizerId);

    expect(await screen.findByText(/Could not look up the registration/)).toBeInTheDocument();
  });

  test('rejects a registration another open proposal already targets', async () => {
    mockVoteRequests.mockReturnValue({
      data: [
        {
          payload: {
            action: {
              tag: 'ARC_DsoRules',
              value: {
                dsoAction: {
                  tag: 'SRARC_ArchiveSynchronizerRegistration',
                  value: { registeredSynchronizerCid: registrationCid },
                },
              },
            },
          },
        },
      ],
    });
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, synchronizerId);

    expect(await screen.findByText(/Another open proposal/)).toBeInTheDocument();
  });

  test('rejects negative weights and more than 10 decimal places', async () => {
    const user = userEvent.setup();
    renderForm();
    await enterSynchronizerId(user, synchronizerId);
    const input = await screen.findByTestId(weightInput);

    await user.clear(input);
    await user.type(input, '-1');
    await user.click(screen.getByTestId(`${prefix}-action`));
    screen.getByText('Weight must be a valid non-negative number');

    await user.clear(input);
    await user.type(input, '0.12345678901');
    await user.click(screen.getByTestId(`${prefix}-action`));
    screen.getByText('Weight can have at most 10 decimal places');

    // The template only requires a weight of at least 0, so 0 and weights above 1 are valid.
    for (const weight of ['0', '2.5']) {
      await user.clear(input);
      await user.type(input, weight);
      await user.click(screen.getByTestId(`${prefix}-action`));
      expect(screen.getByTestId(`${weightInput}-error`).textContent).toBe('');
    }
  });

  test('rejects a paymaster that is not a party id', async () => {
    const user = userEvent.setup();
    renderForm();
    await enterSynchronizerId(user, synchronizerId);
    const input = await screen.findByTestId(`${prefix}-paymaster`);

    await user.clear(input);
    await user.type(input, 'not-a-party');
    await user.click(screen.getByTestId(`${prefix}-action`));

    expect(await screen.findByText(/Invalid PartyId format/)).toBeInTheDocument();
  });

  test('requires at least one value to change', async () => {
    const user = userEvent.setup();
    renderForm();
    await enterSynchronizerId(user, synchronizerId);
    const input = await screen.findByTestId(weightInput);

    // The same value written differently is still no change.
    await user.clear(input);
    await user.type(input, '1.0000000000');
    expect(await screen.findByText(/are the current ones/)).toBeInTheDocument();

    await user.clear(input);
    await user.type(input, '2.5');
    await waitFor(() => expect(screen.queryByText(/are the current ones/)).not.toBeInTheDocument());
  });

  test('reviews the changed values, current and proposed side by side', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByTestId(`${prefix}-summary`), 'Raise the activity weight');
    await user.type(screen.getByTestId(`${prefix}-url`), 'https://example.com');
    await enterSynchronizerId(user, synchronizerId);
    const input = await screen.findByTestId(weightInput);
    await user.clear(input);
    await user.type(input, '2.5');

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(() => expect(submitButton.getAttribute('disabled')).toBeNull());
    await user.click(submitButton);

    await screen.findByTestId('proposal-review');
    expect(screen.getByTestId('registeredSynchronizerCid-field').textContent).toBe(registrationCid);
    const changes = screen.getByTestId('registrationChanges-field');
    expect(within(changes).getAllByTestId('config-change')).toHaveLength(1);
    expect(within(changes).getByTestId('config-change-current-value').textContent).toBe('1.0');
    expect(within(changes).getByTestId('config-change-new-value').textContent).toBe('2.5');
  });

  test('keeps an edited value when the registration is looked up again', async () => {
    const user = userEvent.setup();
    renderForm();
    await enterSynchronizerId(user, synchronizerId);
    const input = await screen.findByTestId(weightInput);
    await waitFor(() => expect(input.getAttribute('value')).toBe('1.0'));
    // Typing the id also scheduled a debounced lookup, which the blur does not cancel. Let it
    // land, so the count below is only the lookup the second blur runs.
    await waitFor(() => expect(mockListRegistrations).toHaveBeenCalledTimes(2));
    await user.clear(input);
    await user.type(input, '2.5');

    // Blurring the synchronizer id runs its lookup again, as submitting does.
    await user.click(screen.getByTestId(`${prefix}-synchronizer-id`));
    await user.click(screen.getByTestId(`${prefix}-action`));
    await waitFor(() => expect(mockListRegistrations).toHaveBeenCalledTimes(3));

    expect(screen.getByTestId(weightInput).getAttribute('value')).toBe('2.5');
  });

  test('a slow lookup on review does not undo an edited value', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByTestId(`${prefix}-summary`), 'Raise the activity weight');
    await user.type(screen.getByTestId(`${prefix}-url`), 'https://example.com');
    await enterSynchronizerId(user, synchronizerId);
    const input = await screen.findByTestId(weightInput);
    await waitFor(() => expect(input.getAttribute('value')).toBe('1.0'));
    await user.clear(input);
    await user.type(input, '2.5');

    // Submitting re-runs the lookup; against a real backend it answers after a delay.
    mockListRegistrations.mockImplementation(
      () =>
        new Promise(resolve =>
          setTimeout(() => resolve({ registrations: [activeSynchronizerRegistration] }), 50)
        )
    );
    const submitButton = screen.getByTestId('submit-button');
    await waitFor(() => expect(submitButton.getAttribute('disabled')).toBeNull());
    await user.click(submitButton);

    await screen.findByTestId('proposal-review');
    expect(screen.queryByText(/are the current ones/)).not.toBeInTheDocument();
    const changes = screen.getByTestId('registrationChanges-field');
    expect(within(changes).getByTestId('config-change-new-value').textContent).toBe('2.5');
  });

  test('proposes the update with all four new values', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByTestId(`${prefix}-summary`), 'Change the paymaster');
    await user.type(screen.getByTestId(`${prefix}-url`), 'https://example.com');
    await enterSynchronizerId(user, synchronizerId);
    const paymasterInput = await screen.findByTestId(`${prefix}-paymaster`);
    await waitFor(() =>
      expect(paymasterInput.getAttribute('value')).toBe(
        activeSynchronizerRegistrationPayload.paymaster
      )
    );
    await user.clear(paymasterInput);
    await user.type(paymasterInput, 'new-paymaster::1220abcdef');

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(() => expect(submitButton.getAttribute('disabled')).toBeNull());
    await user.click(submitButton); // review proposal
    await screen.findByTestId('proposal-review');
    await user.click(submitButton); // submit proposal

    await waitFor(() => expect(mockCreateVoteRequest).toHaveBeenCalledTimes(1));
    expect(mockCreateVoteRequest.mock.calls[0][1]).toEqual({
      tag: 'ARC_DsoRules',
      value: {
        dsoAction: {
          tag: 'SRARC_UpdateSynchronizerRegistration',
          value: {
            registeredSynchronizerCid: registrationCid,
            update: {
              newPaymaster: 'new-paymaster::1220abcdef',
              newMaxActivityWeight: '1.0',
              newMaxAssetActivityWeight: '0.5',
            },
          },
        },
      },
    });
  });
});

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { ListDsoRulesVoteRequestsResponse } from '@canton-network/sv-openapi';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { SvConfigProvider } from '../../../utils';
import App from '../../../App';
import { svPartyId, voteRequests } from '../../mocks/constants';
import { Wrapper } from '../../helpers';
import { CreateFeaturedAppConfigUpdateForm } from '../../../components/forms/CreateFeaturedAppConfigUpdateForm';
import { server, svUrl } from '../../setup/setup';

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

    expect(await screen.findAllByDisplayValue(svPartyId)).not.toHaveLength(0);
  });
});

describe('Create Featured App Config Update Form (amend)', () => {
  const idPrefix = 'amend-featured-app';

  // Wraps an action in an in-flight vote response, reusing a valid vote-request envelope from the
  // mocks and swapping only its action (decodeOpenAPI reads `payload`, not `created_event_blob`).
  const inFlightVoteWith = (
    action: ActionRequiringConfirmation
  ): ListDsoRulesVoteRequestsResponse => {
    const base = voteRequests.dso_rules_vote_requests[0];
    const payload = { ...(base.payload as Record<string, unknown>), action };
    return {
      dso_rules_vote_requests: [{ ...base, payload }],
    };
  };

  const grantAction = (opsParties: string[]): ActionRequiringConfirmation => ({
    tag: 'ARC_DsoRules',
    value: {
      dsoAction: {
        tag: 'SRARC_GrantFeaturedAppRight',
        value: {
          provider: 'in-flight-provider::1014912490',
          activityWeight: null,
          appKind: null,
          opsParties,
        },
      },
    },
  });

  const amendAction = (opsParties: string[]): ActionRequiringConfirmation => ({
    tag: 'ARC_DsoRules',
    value: {
      dsoAction: {
        tag: 'SRARC_CreateFeaturedAppConfigUpdateInstruction',
        value: {
          updates: [
            {
              _1: 'in-flight-provider::1014912490',
              _2: {
                baseConfig: { appKind: null, activityWeight: null, opsParties: null },
                newConfig: { appKind: null, activityWeight: null, opsParties },
              },
            },
          ],
        },
      },
    },
  });

  const addOpsPartyRow = async (user: ReturnType<typeof userEvent.setup>, value: string) => {
    await user.click(screen.getByTestId(`${idPrefix}-opsParties-add`));
    await user.type(screen.getByTestId(`${idPrefix}-opsParties-row-0`), value);
  };

  test('warns when an opsParty overlaps an in-flight grant vote request', async () => {
    const overlapParty = 'overlap-ops::1014912493';
    server.use(
      http.get(`${svUrl}/v0/admin/sv/voterequests`, () =>
        HttpResponse.json(inFlightVoteWith(grantAction([overlapParty])))
      )
    );

    const user = userEvent.setup();
    render(
      <Wrapper>
        <CreateFeaturedAppConfigUpdateForm />
      </Wrapper>
    );

    await addOpsPartyRow(user, overlapParty);

    await waitFor(() => {
      expect(screen.getByTestId(`${idPrefix}-ops-overlap`)).toHaveTextContent(overlapParty);
    });
  });

  test('warns when an opsParty overlaps an in-flight amend vote request', async () => {
    const overlapParty = 'overlap-ops::1014912493';
    server.use(
      http.get(`${svUrl}/v0/admin/sv/voterequests`, () =>
        HttpResponse.json(inFlightVoteWith(amendAction([overlapParty])))
      )
    );

    const user = userEvent.setup();
    render(
      <Wrapper>
        <CreateFeaturedAppConfigUpdateForm />
      </Wrapper>
    );

    await addOpsPartyRow(user, overlapParty);

    await waitFor(() => {
      expect(screen.getByTestId(`${idPrefix}-ops-overlap`)).toHaveTextContent(overlapParty);
    });
  });

  test('does not warn when opsParties do not overlap an in-flight vote request', async () => {
    server.use(
      http.get(`${svUrl}/v0/admin/sv/voterequests`, () =>
        HttpResponse.json(inFlightVoteWith(grantAction(['other-ops::1014912491'])))
      )
    );

    const user = userEvent.setup();
    render(
      <Wrapper>
        <CreateFeaturedAppConfigUpdateForm />
      </Wrapper>
    );

    await addOpsPartyRow(user, 'unique-ops::1014912492');

    // Give the vote-requests query time to resolve, then confirm no warning surfaced.
    await waitFor(() => {
      expect(screen.getByTestId(`${idPrefix}-opsParties-row-0`)).toHaveValue(
        'unique-ops::1014912492'
      );
    });
    expect(screen.queryByTestId(`${idPrefix}-ops-overlap`)).not.toBeInTheDocument();
  });
});

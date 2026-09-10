// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { Wrapper } from '../../helpers';
import { RepermissionValidatorForm } from '../../../components/forms/RepermissionValidatorForm';
import { server, svUrl } from '../../setup/setup';
import { http, HttpResponse } from 'msw';
import {
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  PROPOSAL_SUMMARY_SUBTITLE,
  SUPPORTING_URL_PLACEHOLDER,
} from '../../../utils/constants';

describe('Repermission Validator Form', () => {
  test('should render all Repermission Validator Form components', () => {
    render(
      <Wrapper>
        <RepermissionValidatorForm />
      </Wrapper>
    );

    expect(screen.getByTestId('repermission-validator-form')).toBeInTheDocument();
    expect(screen.getByText(CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE)).toBeInTheDocument();

    const actionInput = screen.getByTestId('repermission-validator-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.textContent).toBe('Repermission Validator');

    const participantInput = screen.getByTestId('repermission-validator-participant-id');
    expect(participantInput).toBeInTheDocument();
    expect(participantInput.getAttribute('value')).toBe('');

    const summaryInput = screen.getByTestId('repermission-validator-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const summarySubtitle = screen.getByTestId('repermission-validator-summary-subtitle');
    expect(summarySubtitle).toBeInTheDocument();
    expect(summarySubtitle.textContent).toBe(PROPOSAL_SUMMARY_SUBTITLE);

    const urlInput = screen.getByTestId('repermission-validator-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');
    expect(urlInput).toHaveAttribute('placeholder', SUPPORTING_URL_PLACEHOLDER);

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
  });

  test('participant ID must be valid format', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <RepermissionValidatorForm />
      </Wrapper>
    );

    const participantInput = screen.getByTestId('repermission-validator-participant-id');
    const actionInput = screen.getByTestId('repermission-validator-action');

    await user.type(participantInput, 'invalid-participant-id');
    await user.click(actionInput);

    await waitFor(() => {
      expect(
        screen.getByText(
          'Invalid ParticipantId format. Expected format: PAR::identifier::fingerprint'
        )
      ).toBeInTheDocument();
    });

    server.use(
      http.get(`${svUrl}/v0/admin/validator/unpermissions/:participant_id`, ({ params }) => {
        const participantId = decodeURIComponent(params.participant_id as string);
        expect(participantId).toBe('PAR::alice::1234567890');

        return HttpResponse.json({
          unpermissions: [{ contract_id: '00abcd1234' }],
        });
      })
    );

    await user.clear(participantInput);
    await user.type(participantInput, 'PAR::alice::1234567890');
    await user.click(actionInput);

    await waitFor(() => {
      expect(
        screen.queryByText(
          'Invalid ParticipantId format. Expected format: PAR::identifier::fingerprint'
        )
      ).not.toBeInTheDocument();
    });
  });
});

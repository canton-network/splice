// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { useState } from 'react';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

import { useAppForm } from '../../hooks/form';
import { useListDsoRulesVoteRequests } from '../../hooks/useListVoteRequests';
import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useDsoInfos } from '../../contexts/SvContext';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import { createProposalActions, getInitialExpiration } from '../../utils/governance';
import { CommonProposalFormData } from '../../utils/types';
import {
  CREATE_PROPOSAL_LABEL_EFFECTIVE_AT,
  CREATE_PROPOSAL_LABEL_PROPOSAL_SUMMARY,
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  CREATE_PROPOSAL_LABEL_SUPPORTING_URL,
  CREATE_PROPOSAL_LABEL_THRESHOLD_DEADLINE,
  SUPPORTING_URL_PLACEHOLDER,
  THRESHOLD_DEADLINE_SUBTITLE,
} from '../../utils/constants';
import {
  validateDiscountFactor,
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validatePartyId,
  validateSummary,
  validateSynchronizerId,
  validateUrl,
} from './formValidators';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { ProposalSummary } from '../governance/ProposalSummary';
import { FormLayout } from './FormLayout';

interface ExtraFormField {
  synchronizerId: string;
  operator: string;
  /** Stated on every registration: 1 is the no-discount value, not an absent one. */
  discountFactor: string;
}

export type RegisterSynchronizerFormData = CommonProposalFormData & ExtraFormField;

/** The no-discount value. A registration states its parameters rather than defaulting them. */
const DEFAULT_DISCOUNT_FACTOR = '1.0';

export const RegisterSynchronizerForm: React.FC = _ => {
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);
  const mutation = useProposalMutation();

  const createProposalAction = createProposalActions.find(
    a => a.value === 'SRARC_RegisterSynchronizer'
  );
  const { lookupSynchronizerRegistration } = useSvAdminClient();
  const voteRequestsQuery = useListDsoRulesVoteRequests();

  const defaultValues: RegisterSynchronizerFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: {
      type: 'custom',
      effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    },
    url: '',
    summary: '',
    synchronizerId: '',
    operator: '',
    discountFactor: DEFAULT_DISCOUNT_FACTOR,
  };

  const form = useAppForm({
    defaultValues,

    onSubmit: async ({ value }) => {
      const discountFactor = value.discountFactor.trim();
      const action: ActionRequiringConfirmation = {
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_RegisterSynchronizer',
            value: {
              synchronizerId: value.synchronizerId,
              operator: value.operator,
              // The same vote sets the parameters, so a synchronizer promised a discount is
              // never registered at the full price while a second vote is arranged.
              governanceParameters: { discountFactor },
            },
          },
        },
      };

      if (!showConfirmation) {
        setShowConfirmation(true);
      } else {
        await mutation.mutateAsync({ formData: value, action }).catch(e => {
          console.error(`Failed to submit proposal`, e);
        });
      }
    },

    validators: {
      onChange: ({ value }) => {
        return validateExpiryEffectiveDate({
          expiration: value.expiryDate,
          effectiveDate: value.effectiveDate.effectiveDate,
        });
      },
    },
  });

  // Uniqueness of the registry is enforced off-ledger, so this check is the enforcement:
  // DsoRules_RegisterSynchronizer only requires a non-empty id and would create a duplicate,
  // which then has to be archived by an offboard vote. A duplicate is a validation error
  // rather than a hint, so the proposal cannot be submitted with an id that is already taken.
  const validateNotAlreadyRegistered = async (value: string) => {
    const id = value.trim();
    if (validateSynchronizerId(id)) return undefined;

    // A duplicate can also be a second proposal in flight, which no on-ledger state shows yet.
    const proposed = (voteRequestsQuery.data ?? []).some(vr => {
      const action = vr.payload.action;
      if (action.tag !== 'ARC_DsoRules') return false;
      const dsoAction = action.value.dsoAction;
      return (
        dsoAction.tag === 'SRARC_RegisterSynchronizer' && dsoAction.value.synchronizerId === id
      );
    });
    if (proposed) return 'Another open proposal already asks to register this synchronizer id.';

    try {
      const registration = await lookupSynchronizerRegistration(id);
      return registration
        ? 'This synchronizer id is already registered. Registering it again creates a duplicate, which has to be archived by an offboard vote.'
        : undefined;
    } catch (e) {
      // The lookup being unavailable is not evidence of a duplicate, so it does not block the
      // proposal: the vote is the last check either way.
      console.error('Failed to look up the synchronizer registration', e);
      return undefined;
    }
  };

  return (
    <FormLayout
      form={form}
      id="register-synchronizer-form"
      actionName={form.state.values.action}
      isReviewStep={showConfirmation}
    >
      {showConfirmation ? (
        <ProposalSummary
          actionName={form.state.values.action}
          url={form.state.values.url}
          summary={form.state.values.summary}
          expiryDate={form.state.values.expiryDate}
          effectiveDate={form.state.values.effectiveDate.effectiveDate}
          formType="register-synchronizer"
          synchronizerId={form.state.values.synchronizerId}
          operator={form.state.values.operator}
          discountFactor={form.state.values.discountFactor.trim()}
          onEdit={() => setShowConfirmation(false)}
          onSubmit={() => {}}
        />
      ) : (
        <>
          <form.AppField name="action">
            {field => (
              <field.ProposalTypeField
                id="register-synchronizer-action"
                title={CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE}
              />
            )}
          </form.AppField>

          <form.AppField
            name="synchronizerId"
            validators={{
              onBlur: ({ value }) => validateSynchronizerId(value),
              onChange: ({ value }) => validateSynchronizerId(value),
              onChangeAsyncDebounceMs: 500,
              onChangeAsync: ({ value }) => validateNotAlreadyRegistered(value),
              onBlurAsync: ({ value }) => validateNotAlreadyRegistered(value),
            }}
          >
            {field => (
              <field.TextField
                title="Synchronizer ID"
                id="register-synchronizer-synchronizer-id"
                subtitle="The id of the dedicated synchronizer to register, as name::fingerprint"
              />
            )}
          </form.AppField>

          <form.AppField
            name="operator"
            validators={{
              onBlur: ({ value }) => validatePartyId(value),
              onChange: ({ value }) => validatePartyId(value),
            }}
          >
            {field => (
              <field.TextField
                title="Synchronizer Operator"
                id="register-synchronizer-operator"
                subtitle="The party that operates the synchronizer and observes its traffic purchases"
              />
            )}
          </form.AppField>

          <form.AppField
            name="discountFactor"
            validators={{
              onBlur: ({ value }) => validateDiscountFactor(value.trim()),
              onChange: ({ value }) => validateDiscountFactor(value.trim()),
            }}
          >
            {field => (
              <field.TextField
                title="Traffic Discount"
                id="register-synchronizer-discount-factor"
                subtitle="Multiplies this synchronizer's traffic price, greater than 0 and at most 1. 1 registers at the full price."
              />
            )}
          </form.AppField>

          <form.AppField
            name="expiryDate"
            validators={{
              onChange: ({ value }) => validateExpiration(value),
              onBlur: ({ value }) => validateExpiration(value),
            }}
          >
            {field => (
              <field.DateField
                title={CREATE_PROPOSAL_LABEL_THRESHOLD_DEADLINE}
                description={THRESHOLD_DEADLINE_SUBTITLE}
                id="register-synchronizer-expiry-date"
              />
            )}
          </form.AppField>

          <form.AppField
            name="effectiveDate"
            validators={{
              onChange: ({ value }) => validateEffectiveDate(value),
              onBlur: ({ value }) => validateEffectiveDate(value),
            }}
            children={_ => (
              <EffectiveDateField
                title={CREATE_PROPOSAL_LABEL_EFFECTIVE_AT}
                initialEffectiveDate={initialEffectiveDate.format(dateTimeFormatISO)}
                id="register-synchronizer-effective-date"
              />
            )}
          />

          <form.AppField
            name="summary"
            validators={{
              onBlur: ({ value }) => validateSummary(value),
              onChange: ({ value }) => validateSummary(value),
            }}
          >
            {field => (
              <field.ProposalSummaryField
                id="register-synchronizer-summary"
                title={CREATE_PROPOSAL_LABEL_PROPOSAL_SUMMARY}
              />
            )}
          </form.AppField>

          <form.AppField
            name="url"
            validators={{
              onBlur: ({ value }) => validateUrl(value),
              onChange: ({ value }) => validateUrl(value),
            }}
          >
            {field => (
              <field.TextField
                title={CREATE_PROPOSAL_LABEL_SUPPORTING_URL}
                id="register-synchronizer-url"
                muiTextFieldProps={{ placeholder: SUPPORTING_URL_PLACEHOLDER }}
              />
            )}
          </form.AppField>
        </>
      )}

      <form.AppForm>
        <ProposalSubmissionError error={mutation.error} />

        <form.FormErrors />

        <form.FormControls
          showConfirmation={showConfirmation}
          onEdit={() => setShowConfirmation(false)}
        />
      </form.AppForm>
    </FormLayout>
  );
};

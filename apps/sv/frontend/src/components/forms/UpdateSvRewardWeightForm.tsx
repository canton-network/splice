// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { useAppForm } from '../../hooks/form';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import { useDsoInfos } from '../../contexts/SvContext';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { FormLayout } from './FormLayout';
import { useMemo, useState } from 'react';
import {
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validateSummary,
  validateSvSelection,
  validateUrl,
  validateWeight,
} from './formValidators';
import {
  CREATE_PROPOSAL_LABEL_EFFECTIVE_AT,
  CREATE_PROPOSAL_LABEL_MEMBER,
  CREATE_PROPOSAL_LABEL_PROPOSAL_SUMMARY,
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  CREATE_PROPOSAL_LABEL_SUPPORTING_URL,
  CREATE_PROPOSAL_LABEL_THRESHOLD_DEADLINE,
  CREATE_PROPOSAL_LABEL_WEIGHT,
  SUPPORTING_URL_PLACEHOLDER,
  THRESHOLD_DEADLINE_SUBTITLE,
} from '../../utils/constants';
import {
  createProposalActions,
  formatBasisPoints,
  getInitialExpiration,
  getSvRewardWeight,
} from '../../utils/governance';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { CommonProposalFormData } from '../../utils/types';
import { ProposalSummary } from '../governance/ProposalSummary';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';
import { useProposalMutation } from '../../hooks/useProposalMutation';

interface ExtraFormField {
  sv: string;
  weight: string;
}

export type UpdateSvRewardWeightFormData = CommonProposalFormData & ExtraFormField;

const LEADING_ZEROS = /^0+(?=\d)/;

export const UpdateSvRewardWeightForm: React.FC = _ => {
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const [showConfirmation, setShowConfirmation] = useState(false);
  const mutation = useProposalMutation();

  const svs = useMemo(
    () => dsoInfosQuery.data?.dsoRules.payload.svs.entriesArray() || [],
    [dsoInfosQuery]
  );

  const svOptions: { key: string; value: string }[] = useMemo(
    () => svs.map(([partyId, svInfo]) => ({ key: svInfo.name, value: partyId })),
    [svs]
  );

  const createProposalAction = createProposalActions.find(
    a => a.value === 'SRARC_UpdateSvRewardWeight'
  );

  const defaultValues: UpdateSvRewardWeightFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: {
      type: 'custom',
      effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    },
    url: '',
    summary: '',
    sv: '',
    weight: '',
  };

  const form = useAppForm({
    defaultValues,

    onSubmit: async ({ value }) => {
      const action: ActionRequiringConfirmation = {
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_UpdateSvRewardWeight',
            value: {
              svParty: value.sv,
              newRewardWeight: value.weight.replace('_', '').replace(LEADING_ZEROS, ''),
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

  const selectedSv = svOptions.find(o => o.value === form.state.values.sv);

  const currentWeight = useMemo(() => {
    return formatBasisPoints(getSvRewardWeight(svs, selectedSv?.value || ''));
  }, [svs, selectedSv]);

  return (
    <>
      <FormLayout
        form={form}
        id="update-sv-reward-weight-form"
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
            formType="sv-reward-weight"
            currentWeight={currentWeight}
            svRewardWeightMember={form.state.values.sv}
            svRewardWeight={form.state.values.weight}
            onEdit={() => setShowConfirmation(false)}
            onSubmit={() => {}}
          />
        ) : (
          <>
            <form.AppField name="action">
              {field => (
                <field.ProposalTypeField
                  id="update-sv-reward-weight-action"
                  title={CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE}
                />
              )}
            </form.AppField>

            <form.AppField
              name="sv"
              validators={{
                onBlur: ({ value }) => validateSvSelection(value),
                onChange: ({ value }) => {
                  return validateSvSelection(value);
                },
              }}
            >
              {field => (
                <field.SelectField
                  title={CREATE_PROPOSAL_LABEL_MEMBER}
                  options={svOptions}
                  id="update-sv-reward-weight-member"
                  onChange={() => form.resetField('weight')}
                />
              )}
            </form.AppField>

            <form.AppField
              name="weight"
              validators={{
                onBlur: ({ value }) => validateWeight(value),
                onChange: ({ value }) => validateWeight(value),
              }}
            >
              {field => (
                <field.TextField
                  title={CREATE_PROPOSAL_LABEL_WEIGHT}
                  id="update-sv-reward-weight-weight"
                  subtitle={selectedSv ? `Current Weight: ${currentWeight}` : undefined}
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
                  id="update-sv-reward-weight-expiry-date"
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
                  id="update-sv-reward-weight-effective-date"
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
                  id="update-sv-reward-weight-summary"
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
                  id="update-sv-reward-weight-url"
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
    </>
  );
};

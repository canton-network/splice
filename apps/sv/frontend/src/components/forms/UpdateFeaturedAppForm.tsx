// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React, { useState, useEffect } from 'react';
import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useDsoInfos } from '../../contexts/SvContext';
import { useFeaturedAppRightPicker } from '../../hooks/useFeaturedAppRightPicker';
import { useProposalMutation } from '../../hooks/useProposalMutation';
import { UpdateFeatureAppFormData } from '../../utils/types';
import { createProposalActions, getInitialExpiration } from '../../utils/governance';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { useAppForm } from '../../hooks/form';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { FeaturedAppRight } from '@daml.js/splice-amulet/lib/Splice/Amulet';
import {
  validateEffectiveDate,
  validateExpiration,
  validateExpiryEffectiveDate,
  validatePartyId,
  validateReason,
  validateRequiredActivityWeight,
  validateSummary,
  validateUrl,
} from './formValidators';
import { FormLayout } from './FormLayout';
import { ProposalSummary } from '../governance/ProposalSummary';
import { useStore } from '@tanstack/react-form';
import { THRESHOLD_DEADLINE_SUBTITLE } from '../../utils/constants';
import { EffectiveDateField } from '../form-components/EffectiveDateField';
import { ProposalSubmissionError } from '../form-components/ProposalSubmissionError';

export const UpdateFeaturedAppForm: React.FC = () => {
  const svAdminClient = useSvAdminClient();
  const dsoInfosQuery = useDsoInfos();
  const initialExpiration = getInitialExpiration(dsoInfosQuery.data);
  const initialEffectiveDate = dayjs(initialExpiration).add(1, 'day');
  const picker = useFeaturedAppRightPicker(svAdminClient);
  const [showConfirmation, setShowConfirmation] = useState(false);
  const mutation = useProposalMutation();
  const idPrefix = 'update-featured-app';

  const createProposalAction = createProposalActions.find(
    a => a.value === 'SRARC_UpdateFeaturedAppRight'
  );

  const defaultValues: UpdateFeatureAppFormData = {
    action: createProposalAction?.name || '',
    expiryDate: initialExpiration.format(dateTimeFormatISO),
    effectiveDate: {
      type: 'custom',
      effectiveDate: initialEffectiveDate.format(dateTimeFormatISO),
    },
    url: '',
    summary: '',
    partyId: '',
    rightCid: '',
    newActivityWeight: '',
    reason: '',
  };

  const form = useAppForm({
    defaultValues,
    onSubmit: async ({ value }) => {
      const action: ActionRequiringConfirmation = {
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_UpdateFeaturedAppRight',
            value: {
              rightCid: value.rightCid as ContractId<FeaturedAppRight>,
              update: { reason: value.reason, newActivityWeight: value.newActivityWeight },
            },
          },
        },
      };
      if (!showConfirmation) setShowConfirmation(true);
      else
        await mutation.mutateAsync({ formData: value, action }).catch(e => {
          console.error('Failed to submit proposal', e);
        });
    },
    validators: {
      onChange: ({ value }) =>
        validateExpiryEffectiveDate({
          expiration: value.expiryDate,
          effectiveDate: value.effectiveDate.effectiveDate,
        }),
    },
  });

  useEffect(() => {
    const currentRightCid = form.state.values.rightCid;
    const hasSelectedOption = picker.rightOptions.some(o => o.value === currentRightCid);
    if (hasSelectedOption) return;

    const nextRightCid = picker.rightOptions.length === 1 ? picker.rightOptions[0].value : '';
    form.setFieldValue('rightCid', nextRightCid);
  }, [form, picker.rightOptions]);

  const partyId = useStore(form.store, state => state.values.partyId);
  const rightCid = useStore(form.store, state => state.values.rightCid);
  const currentWeight = picker.currentWeights[rightCid] ?? 'None';

  const providerHasNoRights =
    picker.providerSearched && picker.rightOptions.length === 0 && !validatePartyId(partyId);

  const requiredActivityWeightSubtitle =
    "Required. Scales the app's share of traffic-based rewards";

  return (
    <>
      <FormLayout form={form} id={`${idPrefix}-form`}>
        {showConfirmation ? (
          <ProposalSummary
            actionName={form.state.values.action}
            url={form.state.values.url}
            summary={form.state.values.summary}
            expiryDate={form.state.values.expiryDate}
            effectiveDate={form.state.values.effectiveDate.effectiveDate}
            formType="update-right-weight"
            providerPartyId={form.state.values.partyId}
            rightCid={form.state.values.rightCid}
            newActivityWeight={form.state.values.newActivityWeight}
            currentActivityWeight={currentWeight}
            reason={form.state.values.reason}
            onEdit={() => setShowConfirmation(false)}
            onSubmit={() => {}}
          />
        ) : (
          <>
            <form.AppField name="action">
              {field => <field.ProposalTypeField id={`${idPrefix}-action`} />}
            </form.AppField>

            <form.AppField
              name="partyId"
              validators={{
                onChange: ({ value }) => validatePartyId(value),
                onChangeAsyncDebounceMs: 500,
                onChangeAsync: ({ value }) => picker.loadFeaturedAppRightsAndValidate(value),
              }}
            >
              {field => (
                <field.TextField
                  title={'Provider Party ID'}
                  id={`${idPrefix}-partyId`}
                  subtitle={
                    field.state.meta.isValidating ? 'Loading featured app rights...' : undefined
                  }
                  onChange={() => {
                    picker.resetOptions();
                  }}
                />
              )}
            </form.AppField>

            <form.AppField
              name="rightCid"
              validators={{
                onBlur: ({ value }) => picker.validateRightSelection(value),
                onChange: ({ value }) => picker.validateRightSelection(value),
              }}
            >
              {field => (
                <field.SelectField
                  title="Featured Application Contract ID"
                  id={`${idPrefix}-rightCid`}
                  options={picker.rightOptions}
                  disabled={picker.rightOptions.length === 0}
                  placeholder={
                    providerHasNoRights
                      ? 'No featured application rights found for this provider'
                      : undefined
                  }
                />
              )}
            </form.AppField>

            <form.AppField
              name="newActivityWeight"
              validators={{
                onBlur: ({ value }) => validateRequiredActivityWeight(value),
                onChange: ({ value }) => validateRequiredActivityWeight(value),
              }}
            >
              {field => (
                <field.TextField
                  title="Activity Weight"
                  id={`${idPrefix}-activityWeight`}
                  subtitle={
                    rightCid
                      ? `Current Weight: ${currentWeight}. ${requiredActivityWeightSubtitle}`
                      : requiredActivityWeightSubtitle
                  }
                />
              )}
            </form.AppField>

            <form.AppField
              name="reason"
              validators={{
                onBlur: ({ value }) => validateReason(value),
                onChange: ({ value }) => validateReason(value),
              }}
            >
              {field => <field.TextField title="Proposal Reason" id={`${idPrefix}-reason`} />}
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
                  title="Threshold Deadline"
                  description={THRESHOLD_DEADLINE_SUBTITLE}
                  id={`${idPrefix}-expiry-date`}
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
                  initialEffectiveDate={initialEffectiveDate.format(dateTimeFormatISO)}
                  id={`${idPrefix}-effective-date`}
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
              {field => <field.ProposalSummaryField id={`${idPrefix}-summary`} />}
            </form.AppField>

            <form.AppField
              name="url"
              validators={{
                onBlur: ({ value }) => validateUrl(value),
                onChange: ({ value }) => validateUrl(value),
              }}
            >
              {field => <field.TextField title="URL" id={`${idPrefix}-url`} />}
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

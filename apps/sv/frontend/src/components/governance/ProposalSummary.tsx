// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import {
  EFFECTIVE_AT_LABEL,
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  PROPOSAL_REVIEW_TITLE,
  THRESHOLD_DEADLINE_LABEL,
  THRESHOLD_DEADLINE_SUBTITLE,
} from '../../utils/constants';
import type { ConfigChange } from '../../utils/types';
import { ConfigValuesChanges } from './ConfigValuesChanges';
import { ProposalReviewField } from './ProposalReviewField';

interface BaseProposalSummaryProps {
  actionName: string;
  url: string;
  summary: string;
  expiryDate: string;
  effectiveDate: string | undefined;
  onEdit: () => void;
  onSubmit: () => void;
}

type ProposalSummaryProps = BaseProposalSummaryProps &
  (
    | {
        formType: 'sv-reward-weight';
        svRewardWeightMember: string;
        currentWeight: string;
        svRewardWeight: string;
      }
    | {
        formType: 'offboard';
        offboardMember: string;
      }
    | {
        formType: 'grant-right';
        grantRight: string;
        activityWeight: string;
      }
    | {
        formType: 'revoke-right';
        providerPartyId: string;
        revokeRight: string;
      }
    | {
        formType: 'config-change';
        configFormData: ConfigChange[];
        /** Rendered under Proposed Configuration Changes (e.g. Show JSON). */
        jsonDiff?: ReactNode;
      }
    | {
        formType: 'create-unallocated-unclaimed-activity-record';
        beneficiary: string;
        amount: string;
        expiresAt: string;
      }
    | {
        formType: 'update-right-weight';
        providerPartyId: string;
        rightCid: string;
        currentActivityWeight: string;
        newActivityWeight: string;
      }
  );

export const ProposalSummary: React.FC<ProposalSummaryProps> = props => {
  const { formType, actionName, url, summary, expiryDate, effectiveDate } = props;

  return (
    <Box data-testid="proposal-review">
      <Typography
        component="h2"
        data-testid="proposal-review-title"
        sx={{
          fontFamily: "'Inter', sans-serif",
          fontWeight: 700,
          fontSize: '18px',
          lineHeight: '28px',
          letterSpacing: 0,
          color: '#FFFFFF',
          mb: 4,
        }}
      >
        {PROPOSAL_REVIEW_TITLE}
      </Typography>

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <ProposalReviewField
          id="action"
          label={CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE}
          value={actionName}
        />

        {/* Action-specific fields follow Action (Figma: config/member before threshold). */}
        {formType === 'config-change' && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <ProposalReviewField
              id="configChange"
              label="Proposed Configuration Changes"
              value={<ConfigValuesChanges changes={props.configFormData} isSummaryView />}
            />
            {props.jsonDiff}
          </Box>
        )}

        {formType === 'sv-reward-weight' && (
          <>
            <ProposalReviewField
              id="svRewardWeightMember"
              label="Member"
              value={props.svRewardWeightMember}
            />
            <ProposalReviewField
              id="configChange"
              label="Proposed Changes"
              value={
                <ConfigValuesChanges
                  isSummaryView
                  changes={[
                    {
                      label: 'Super Validator Reward Weight',
                      fieldName: 'svRewardWeight',
                      currentValue: props.currentWeight,
                      newValue: props.svRewardWeight,
                    },
                  ]}
                />
              }
            />
          </>
        )}

        {formType === 'grant-right' && (
          <>
            <ProposalReviewField
              id="grantRight"
              label="Provider Party ID"
              value={props.grantRight}
            />
            <ProposalReviewField
              id="grantRightActivityWeight"
              label="Activity Weight"
              value={props.activityWeight}
            />
          </>
        )}

        {formType === 'revoke-right' && (
          <>
            <ProposalReviewField
              id="revokeProviderPartyId"
              label="Provider Party ID"
              value={props.providerPartyId}
            />
            <ProposalReviewField
              id="revokeRight"
              label="Featured Application Contract ID"
              value={props.revokeRight}
            />
          </>
        )}

        {formType === 'update-right-weight' && (
          <>
            <ProposalReviewField
              id="updateProviderPartyId"
              label="Provider Party ID"
              value={props.providerPartyId}
            />
            <ProposalReviewField
              id="updateRight"
              label="Featured Application Contract ID"
              value={props.rightCid}
            />
            <ProposalReviewField
              id="updateActivityWeight"
              label="Proposed Changes"
              value={
                <ConfigValuesChanges
                  isSummaryView
                  changes={[
                    {
                      label: 'Activity Weight',
                      fieldName: 'newActivityWeight',
                      currentValue: props.currentActivityWeight,
                      newValue: props.newActivityWeight,
                    },
                  ]}
                />
              }
            />
          </>
        )}

        {formType === 'offboard' && (
          <ProposalReviewField id="offboardMember" label="Member" value={props.offboardMember} />
        )}

        {formType === 'create-unallocated-unclaimed-activity-record' && (
          <>
            <ProposalReviewField id="beneficiary" label="Beneficiary" value={props.beneficiary} />
            <ProposalReviewField id="amount" label="Amount" value={props.amount} />
            <ProposalReviewField id="expiresAt" label="Must Mint Before" value={props.expiresAt} />
          </>
        )}

        <ProposalReviewField
          id="expiryDate"
          label={THRESHOLD_DEADLINE_LABEL}
          subtitle={THRESHOLD_DEADLINE_SUBTITLE}
          value={expiryDate}
        />

        <ProposalReviewField
          id="effectiveDate"
          label={EFFECTIVE_AT_LABEL}
          value={effectiveDate ? effectiveDate : 'Threshold'}
        />

        <ProposalReviewField id="summary" label="Proposal Summary" value={summary} />

        <ProposalReviewField id="url" label="Supporting URL" value={url} />
      </Box>
    </Box>
  );
};

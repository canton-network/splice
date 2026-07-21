// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Box, Typography } from '@mui/material';
import { THRESHOLD_DEADLINE_SUBTITLE } from '../../utils/constants';
import type { ConfigChange } from '../../utils/types';
import { scrollContainerSx, scrollableIdentifierFieldSx } from '../beta/identifierStyles';
import { ConfigValuesChanges } from './ConfigValuesChanges';

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
        reason: string;
      }
  );

export const ProposalSummary: React.FC<ProposalSummaryProps> = props => {
  const { formType, actionName, url, summary, expiryDate, effectiveDate } = props;

  return (
    <Box>
      <Typography variant="h3" mb={8}>
        Proposal Summary
      </Typography>

      <Box>
        <ProposalField id="action" title="Action" value={actionName} />

        <ProposalField id="url" title="URL" value={url} />

        <ProposalField id="summary" title="Summary" value={summary} />

        <ProposalField
          id="expiryDate"
          title="Threshold Deadline"
          subtitle={THRESHOLD_DEADLINE_SUBTITLE}
          value={expiryDate}
        />

        <ProposalField
          id="effectiveDate"
          title="Effective Date"
          value={effectiveDate ? effectiveDate : 'Threshold'}
        />

        {formType === 'sv-reward-weight' && (
          <>
            <ProposalField
              id="svRewardWeightMember"
              title="Member"
              value={props.svRewardWeightMember}
              scrollableIdentifier
            />
            <ProposalField
              id="configChange"
              title="Proposed Changes"
              value={
                <ConfigValuesChanges
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
            <ProposalField
              id="grantRight"
              title="Provider Party ID"
              value={props.grantRight}
              scrollableIdentifier
            />
            <ProposalField
              id="grantRightActivityWeight"
              title="Activity Weight"
              value={props.activityWeight}
            />
          </>
        )}

        {formType === 'revoke-right' && (
          <>
            <ProposalField
              id="revokeProviderPartyId"
              title="Provider Party ID"
              value={props.providerPartyId}
              scrollableIdentifier
            />
            <ProposalField
              id="revokeRight"
              title="Featured Application Contract ID"
              value={props.revokeRight}
              scrollableIdentifier
            />
          </>
        )}

        {formType === 'update-right-weight' && (
          <>
            <ProposalField
              id="updateProviderPartyId"
              title="Provider Party ID"
              value={props.providerPartyId}
            />
            <ProposalField
              id="updateRight"
              title="Featured Application Contract ID"
              value={props.rightCid}
            />
            <ProposalField
              id="updateActivityWeight"
              title="Proposed Changes"
              value={
                <ConfigValuesChanges
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
            <ProposalField id="updateReason" title="Reason" value={props.reason} />
          </>
        )}

        {formType === 'offboard' && (
          <ProposalField
            id="offboardMember"
            title="Offboard Member"
            value={props.offboardMember}
            scrollableIdentifier
          />
        )}

        {formType === 'create-unallocated-unclaimed-activity-record' && (
          <>
            <ProposalField
              id="beneficiary"
              title="Beneficiary"
              value={props.beneficiary}
              scrollableIdentifier
            />

            <ProposalField id="amount" title="Amount" value={props.amount} />

            <ProposalField id="expiresAt" title="Must Mint Before" value={props.expiresAt} />
          </>
        )}

        <Box mt={4}>
          {formType === 'config-change' && (
            <ProposalField
              id="configChange"
              title="Proposed Changes"
              value={<ConfigValuesChanges changes={props.configFormData} isSummaryView />}
            />
          )}
        </Box>
      </Box>
    </Box>
  );
};

interface ProposalFieldProps {
  id: string;
  title: string;
  subtitle?: string;
  value: React.ReactNode;
  scrollableIdentifier?: boolean;
}

const ProposalField: React.FC<ProposalFieldProps> = props => {
  const { id, title, subtitle, value, scrollableIdentifier = false } = props;

  return (
    <Box sx={{ minWidth: '80%' }}>
      <Typography
        variant="h5"
        id={`${id}-title`}
        data-testid={`${id}-title`}
        gutterBottom
        mb={1}
        mt={4}
      >
        {title}
      </Typography>

      <Box>
        {subtitle && (
          <Typography
            variant="body2"
            id={`${id}-subtitle`}
            data-testid={`${id}-subtitle`}
            gutterBottom
          >
            {subtitle}
          </Typography>
        )}

        {typeof value === 'string' ? (
          scrollableIdentifier ? (
            <Box sx={scrollContainerSx} data-testid={`${id}-field-scroll`}>
              <Typography
                variant="body2"
                color="grey"
                data-testid={`${id}-field`}
                sx={scrollableIdentifierFieldSx}
              >
                {value}
              </Typography>
            </Box>
          ) : (
            <Typography variant="body2" data-testid={`${id}-field`} color="grey">
              {value}
            </Typography>
          )
        ) : (
          value
        )}
      </Box>
    </Box>
  );
};

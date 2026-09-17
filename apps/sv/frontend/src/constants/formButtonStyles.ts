// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  CREATE_PROPOSAL_DISABLED_CTA_BG,
  CREATE_PROPOSAL_DISABLED_CTA_TEXT,
  CREATE_PROPOSAL_DISCARD_CTA,
  CREATE_PROPOSAL_PRIMARY_CTA,
  CREATE_PROPOSAL_SECONDARY_CTA,
} from './createProposalLayout';

const pillButtonBaseSx = {
  height: '39px',
  px: '16px',
  py: '10px',
  borderRadius: '20px',
  textTransform: 'none' as const,
  fontSize: '16px',
  fontWeight: 500,
  fontFamily: "'Inter', sans-serif",
  lineHeight: 'normal',
  boxShadow: 'none',
  minWidth: 'unset',
};

/** Figma Warning/Secondary button — transparent fill, yellow outline, white label. */
export const createProposalCancelButtonSx = {
  ...pillButtonBaseSx,
  bgcolor: 'transparent',
  border: `1px solid ${CREATE_PROPOSAL_SECONDARY_CTA}`,
  color: '#FFFFFF',
  '&:hover': {
    bgcolor: 'transparent',
    border: `1px solid ${CREATE_PROPOSAL_SECONDARY_CTA}`,
    color: CREATE_PROPOSAL_SECONDARY_CTA,
    boxShadow: 'none',
  },
};

/** Figma Warning Button (destructive) — transparent fill, coral outline, white label. */
export const createProposalDiscardButtonSx = {
  ...pillButtonBaseSx,
  bgcolor: 'transparent',
  border: `1px solid ${CREATE_PROPOSAL_DISCARD_CTA}`,
  color: '#FFFFFF',
  '&:hover': {
    bgcolor: 'transparent',
    border: `1px solid ${CREATE_PROPOSAL_DISCARD_CTA}`,
    color: CREATE_PROPOSAL_DISCARD_CTA,
    boxShadow: 'none',
  },
};

/** Figma Primary button — cyan fill, black label; stone disabled state. */
export const createProposalSubmitButtonSx = {
  ...pillButtonBaseSx,
  bgcolor: CREATE_PROPOSAL_PRIMARY_CTA,
  color: '#000000',
  border: 'none',
  '&:hover': {
    bgcolor: CREATE_PROPOSAL_PRIMARY_CTA,
    color: '#000000',
    boxShadow: 'none',
  },
  '&:disabled': {
    bgcolor: CREATE_PROPOSAL_DISABLED_CTA_BG,
    color: CREATE_PROPOSAL_DISABLED_CTA_TEXT,
    border: 'none',
  },
};

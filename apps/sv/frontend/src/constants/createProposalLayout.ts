// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/** Figma field column width inside the card. */
export const CREATE_PROPOSAL_FIELD_MAX_WIDTH = 832;

export const CREATE_PROPOSAL_CARD_BG = '#181818';
export const CREATE_PROPOSAL_CARD_BORDER_RADIUS = '4px';
export const CREATE_PROPOSAL_CARD_PADDING_Y = '60px';

/** Vertical gap between main form sections (Figma). */
export const CREATE_PROPOSAL_SECTION_GAP = '32px';

/** Vertical gap between configuration rows (Figma Frame 535). */
export const CREATE_PROPOSAL_CONFIG_ROW_GAP = '24px';

/** Gap from a configuration row to its divider (Figma Frame 533). */
export const CREATE_PROPOSAL_CONFIG_ROW_DIVIDER_GAP = '14px';

/** Configuration value input width (Figma Frame 531). */
export const CREATE_PROPOSAL_CONFIG_INPUT_WIDTH = '238px';

/** Figma Blue (Primary CTA) — enabled Review/Submit Proposal. */
export const CREATE_PROPOSAL_PRIMARY_CTA = '#96E4FD';

/** Figma Yellow (Secondary CTA) — Cancel outline and JSON toggle. */
export const CREATE_PROPOSAL_SECONDARY_CTA = '#F3FF97';

/** Figma disabled primary CTA surface (stone-500). */
export const CREATE_PROPOSAL_DISABLED_CTA_BG = '#78716C';

/** Figma disabled primary CTA label (neutral 25%). */
export const CREATE_PROPOSAL_DISABLED_CTA_TEXT = '#404040';

/** Figma coral (Warning Button / destructive) — Discard & Exit outline, error icon. */
export const CREATE_PROPOSAL_DISCARD_CTA = '#FD8575';

/** Figma FIELD H — 12px Inter semibold uppercase field labels. */
export const CREATE_PROPOSAL_FIELD_LABEL_SX = {
  fontFamily: "'Inter', sans-serif",
  fontSize: '12px',
  fontWeight: 600,
  lineHeight: '22px',
  letterSpacing: 0,
  textTransform: 'uppercase' as const,
  color: '#E2E2E2',
};

/** Figma Body M — 14px field values and radio option labels. */
export const CREATE_PROPOSAL_FIELD_BODY_SX = {
  fontFamily: "'Inter', sans-serif",
  fontSize: '14px',
  fontWeight: 400,
  lineHeight: '22px',
  letterSpacing: 0,
  color: '#E2E2E2',
};

/** Figma Body S — 12px helper / subtitle text. */
export const CREATE_PROPOSAL_FIELD_HELPER_SX = {
  fontFamily: "'Inter', sans-serif",
  fontSize: '12px',
  fontWeight: 400,
  lineHeight: '22px',
  letterSpacing: 0,
  color: '#E2E2E2',
};

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// SV-local Termina @font-face (Medium 500 + Bold 700) — see fonts.css for why this
// exists alongside @canton-network/splice-common-typeface-termina.
import './fonts.css';

/** Figma tokens from CF-design-system (tokens.md + delegate-election-2 Dev Mode). */
export const layoutTokens = {
  /** Figma surface-page — bg-neutral-800 */
  page: '#262626',
  /** Figma Dev Mode Background-lighter on Navigation component */
  navBackground: '#272727',
  /** Figma Dev Mode --Light-text — brand wordmark and nav labels */
  lightText: '#E2E2E2',
  /** Figma banner-testnet — bg-cyan-100 (styled export uses #C8F1FE) */
  bannerTestnet: '#cffafe',
  bannerText: '#18181b',
  /** Figma red00 — Governance count badge */
  notificationBadge: '#FD8575',
  /** Figma Purple (Navigation) — active nav pill border */
  navActiveOutline: '#875CFF',
  navAttention: '#F3FF97',
  fontBrand: '"Termina", sans-serif',
  fontUi: '"Inter", sans-serif',
} as const;

/** Figma Dev Mode — nav row horizontal padding 50px */
export const PAGE_PX = '50px';

/** Figma p-2.5 — 10px padding on brand box and nav pills */
export const NAV_PILL_PX = '10px';

/** Figma content max width (nav row is full width; content uses this) */
export const CONTENT_MAX_WIDTH = 1583;

/** Figma Dev Mode — 64px space below nav row, present on every page */
export const HEADER_PB = 8;

/** Figma Dev Mode — 30px gap between banner and nav row (Navigation component) */
export const BANNER_HEADER_GAP = 3.75;

/** Figma Dev Mode — fixed 145px gap between brand wordmark and nav cluster. */
export const NAV_BRAND_GAP = '145px';

/** Figma Dev Mode — fixed 60px between nav pills (not responsive). */
export const NAV_GAP = '60px';

/** Figma Dev Mode — nav row height 44px */
export const NAV_ROW_MIN_HEIGHT = 44;

/** Figma Dev Mode — banner height 50px */
export const BANNER_MIN_HEIGHT = 50;

/**
 * Figma Dev Mode — Inter nav/logout typography (letter spacing: 0px, 140% line-height).
 * Without an explicit `letterSpacing` reset, these Box/Typography elements inherit
 * MUI's default body1 letter-spacing (0.00938em) from an ancestor, rendering as a
 * ~0.13-0.15px leak that's invisible in a screenshot but measurable via computed
 * styles and doesn't match Figma's 0px spec. `lineHeight: 'normal'` also falls back
 * to Inter's own metrics rather than the explicit 140% Figma spec, so it's pinned here.
 */
export const navItemTypography = {
  fontFeatureSettings: "'liga' off, 'clig' off",
  lineHeight: 1.4,
  letterSpacing: 0,
} as const;

export const BRAND_TITLE = 'Supervalidator Operations';

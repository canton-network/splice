// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import { z } from 'zod';
import type { EffectivityType } from '../../utils/types';
import { isValidUrl } from '../../utils/validations';
import { nextScheduledSynchronizerUpgradeFormat } from '@canton-network/splice-common-frontend-utils';

dayjs.extend(utc);

export const urlSchema = z.string().refine(url => isValidUrl(url), {
  message: 'Invalid URL',
});

export const summarySchema = z.string().min(1, { message: 'Summary is required' });

export const reasonSchema = z.string().min(1, { message: 'Reason is required' });

export const svSelectionSchema = z.string().min(1, { message: 'SV is required' });

const getExpirationSchema = (errMessage: string) => {
  return z.string().refine(date => dayjs(date).isAfter(dayjs()), {
    message: errMessage,
  });
};

export const expirationSchema = getExpirationSchema('Expiration must be in the future');

export const mintBeforeSchema = getExpirationSchema('Date must be in the future');

export const effectiveDateSchema = z.string().refine(date => dayjs(date).isAfter(dayjs()), {
  message: 'Effective Date must be in the future',
});

export const expiryEffectiveDateSchema = z
  .object({
    expiration: z.string(),
    effectiveDate: z.string(),
  })
  .refine(({ expiration, effectiveDate }) => dayjs(expiration).isBefore(dayjs(effectiveDate)), {
    message: 'Effective Date must be after expiration date',
    path: ['effectiveDate'],
  });

export const revokeFeaturedAppRightSchema = z.string().min(1, { message: 'Required' });

export const partyIdSchema = z
  .string()
  .min(1, { message: 'Required' })
  .regex(/^[a-zA-Z0-9_-]+::[a-zA-Z0-9_-]+$/, {
    message: 'Invalid PartyId format. Expected format: identifier::fingerprint',
  });

export const svWeightSchema = z
  .string()
  .min(1, { message: 'Weight is required' })
  .regex(/^\d+_\d{4}$/, {
    message: 'Weight must be expressed in basis points using fixed point notation, XX...X_XXXX',
  });

export const rewardAmountSchema = z
  .string()
  .min(1, { message: 'Amount is required' })
  .regex(/^\d+(\.\d+)?$/, { message: 'Amount must be a valid number' })
  .refine(
    v => {
      const dotIndex = v.indexOf('.');
      return dotIndex === -1 || v.length - dotIndex - 1 <= 10;
    },
    { message: 'Amount can have at most 10 decimal places' }
  );

export const requiredActivityWeightSchema = z
  .string()
  .min(1, { message: 'Weight is required' })
  .regex(/^\d+(\.\d+)?$/, { message: 'Weight must be a valid non-negative number' })
  .refine(
    v => {
      const i = v.indexOf('.');
      return i === -1 || v.length - i - 1 <= 10;
    },
    { message: 'Weight can have at most 10 decimal places' }
  );

export const activityWeightSchema = z
  .string()
  .refine(v => v === '' || /^\d+(\.\d+)?$/.test(v), {
    message: 'Weight must be a valid non-negative number',
  })
  .refine(
    v => {
      const dotIndex = v.indexOf('.');
      return dotIndex === -1 || v.length - dotIndex - 1 <= 10;
    },
    { message: 'Weight can have at most 10 decimal places' }
  );

export const validateWeight = (value: string): string | false => {
  const result = svWeightSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateRewardAmount = (value: string): string | false => {
  const result = rewardAmountSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateActivityWeight = (value: string): string | false => {
  const result = activityWeightSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateRequiredActivityWeight = (value: string): string | false => {
  const result = requiredActivityWeightSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateSvSelection = (value: string): string | false => {
  const result = svSelectionSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateExpiration = (value: string): string | false => {
  const result = expirationSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateMintBefore = (value: string): string | false => {
  const result = mintBeforeSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateMintedBeneficiary = (value: string): string | false => {
  const schema = z.string().min(1, { message: 'Beneficiary is required' });

  const result = schema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateEffectiveDate = (value: {
  type: EffectivityType;
  effectiveDate: string | undefined;
}): string | false => {
  // nothing to validate if effective at threshold
  if (value.type === 'threshold') return false;

  const result = effectiveDateSchema.safeParse(value.effectiveDate);
  return result.success ? false : result.error.issues[0].message;
};

export const validateExpiryEffectiveDate = (value: {
  expiration: string;
  effectiveDate?: string;
}): string | false => {
  if (!value.effectiveDate) return false;

  const result = expiryEffectiveDateSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateMintBeforeAndEffectiveDate = (value: {
  effectiveDate?: string;
  mintBefore: string;
}): string | false => {
  if (!value.effectiveDate) return false;

  const schema = z
    .object({
      effectiveDate: z.string(),
      mintBefore: z.string(),
    })
    .refine(
      ({ effectiveDate, mintBefore }) =>
        dayjs(mintBefore).isAfter(dayjs(effectiveDate).add(2, 'hour')),
      {
        message: 'Mint Before date must be at least 2 hours after Effective Date',
        path: ['mintBefore'],
      }
    );

  const result = schema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateSummary = (value: string): string | false => {
  const result = summarySchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateReason = (value: string): string | false => {
  const result = reasonSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateUrl = (value: string): string | false => {
  const result = urlSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateRevokeFeaturedAppRight = (value: string): string | false => {
  const result = revokeFeaturedAppRightSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validatePartyId = (value: string): string | false => {
  const result = partyIdSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateNextScheduledSynchronizerUpgrade = (
  upgradeTime: string,
  migrationId: string,
  effectiveDate: string | undefined
): string | false => {
  const onlyOneIsProvided = (upgradeTime === '') !== (migrationId === '');
  const bothEmpty = upgradeTime === '' && migrationId === '';

  if (bothEmpty) {
    return false;
  }

  if (onlyOneIsProvided) {
    return 'Upgrade Time and Migration ID are required for a Scheduled Synchronizer Upgrade';
  }

  const upgradeTimeDate = dayjs.utc(upgradeTime);
  const effectivity = dayjs(effectiveDate);

  const upgradeTimeIsAfterEffectiveDate = upgradeTimeDate.isAfter(effectivity.add(1, 'hour'));
  if (!upgradeTimeIsAfterEffectiveDate) {
    return 'Upgrade Time must be at least 1 hour after the Effective Date';
  }

  return false;
};

export const validateNextScheduledLogicalSynchronizerUpgrade = (
  topologyFreezeTime: string,
  upgradeTime: string,
  newPhyiscalSynchronizerSerial: string,
  newPhyiscalSynchronizerProtocolVersion: string,
  effectiveDate: string | undefined
): string | false => {
  const all = [
    topologyFreezeTime,
    upgradeTime,
    newPhyiscalSynchronizerSerial,
    newPhyiscalSynchronizerProtocolVersion,
  ];

  if (all.every(value => value === '')) {
    return false;
  }

  if (!all.every(value => value !== '')) {
    return 'Topology freeze time, upgrade time, new physical synchronizer serial, and new physical synchronizer protocol version are required for a Scheduled Logical Synchronizer Upgrade';
  }

  const freezeTimeDate = dayjs.utc(topologyFreezeTime);
  const effectivity = dayjs(effectiveDate);

  const freezeTimeIsAfterEffectiveDate = freezeTimeDate.isAfter(effectivity.add(1, 'hour'));
  if (!freezeTimeIsAfterEffectiveDate) {
    return 'Topology Freeze Time must be at least 1 hour after the Effective Date';
  }

  const upgradeTimeDate = dayjs.utc(upgradeTime);
  if (!upgradeTimeDate.isAfter(freezeTimeDate)) {
    return 'Upgrade Time must be after Topology Freeze Time';
  }

  return false;
};

export type SwitchOverEntry = { key: string; time: string };

/**
 * Serialize switch-over entries into the DAML map shape used by the config
 * builders: trim keys, drop entries with an empty key, normalize each time to
 * the DAML `Time` format, and collapse to `null` when there is nothing left.
 * Shared with the builders so change detection matches what is submitted.
 */
export const serializeSwitchOverTimes = (
  entries: SwitchOverEntry[]
): Record<string, string> | null => {
  const trimmed = entries.map(e => ({ key: e.key.trim(), time: e.time })).filter(e => e.key !== '');

  return trimmed.length === 0
    ? null
    : Object.fromEntries(
        trimmed.map(e => [
          e.key,
          dayjs(e.time).utc().format(nextScheduledSynchronizerUpgradeFormat),
        ])
      );
};

/**
 * True when the switch-over entries differ from the baseline map. Both sides
 * are normalized through {@link serializeSwitchOverTimes} so the comparison is
 * format-safe and order-independent, and treats `null`/absent as no entries.
 */
export const switchOverTimesChanged = (
  baseline: Record<string, string> | null | undefined,
  entries: SwitchOverEntry[]
): boolean => {
  const baselineEntries = Object.entries(baseline ?? {}).map(([key, time]) => ({ key, time }));
  const a = serializeSwitchOverTimes(baselineEntries) ?? {};
  const b = serializeSwitchOverTimes(entries) ?? {};
  const ka = Object.keys(a).sort();
  const kb = Object.keys(b).sort();
  if (ka.length !== kb.length) return true;
  return !ka.every((k, i) => k === kb[i] && a[k] === b[k]);
};

export const validateSwitchOverTimes = (
  entries: SwitchOverEntry[],
  allowNonFutureDated: boolean,
  effectiveDate: string | undefined
): string | false => {
  if (entries.length === 0) return false;

  const keys = entries.map(e => e.key.trim());

  if (keys.some(k => k === '')) {
    return 'Switch-over key is required';
  }

  if (new Set(keys).size !== keys.length) {
    return 'Switch-over keys must be unique';
  }

  for (const { key, time } of entries) {
    const t = dayjs.utc(time);
    if (!t.isValid()) {
      return `Invalid time for switch-over "${key.trim()}"`;
    }
    // Skip the ">= 1 day after effectivity" check at threshold (no effective date)
    // or when the operator has opted into non-future-dated times.
    if (!allowNonFutureDated && effectiveDate) {
      const minTime = dayjs.utc(effectiveDate).add(1, 'day');
      if (t.isBefore(minTime)) {
        return `Switch-over "${key.trim()}" must be at least 1 day after the Effective Date`;
      }
    }
  }
  return false;
};

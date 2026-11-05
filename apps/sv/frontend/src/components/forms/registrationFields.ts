// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type { RegisteredSynchronizer } from '@daml.js/splice-amulet/lib/Splice/DecentralizedSynchronizer';
import type { ConfigChange } from '../../utils/types';
import { validatePartyId, validateRequiredActivityWeight } from './formValidators';

// The fields a vote can change. The synchronizer id identifies the registration and is kept.
export type RegistrationFieldKey = keyof Omit<RegisteredSynchronizer, 'dso' | 'synchronizerId'>;
export type RegistrationFields = Pick<RegisteredSynchronizer, RegistrationFieldKey>;
export type RegistrationFieldValues = Record<RegistrationFieldKey, string>;

// Daml renders a Decimal with all ten places, so 1.0 and 1.0000000000 must compare equal.
const normalizeDecimal = (value: string): string => {
  const trimmed = value.trim();
  if (!/^\d+(\.\d+)?$/.test(trimmed)) return trimmed;
  const [whole, fraction = ''] = trimmed.split('.');
  return `${whole.replace(/^0+(?=\d)/, '')}.${fraction.replace(/0+$/, '') || '0'}`;
};

type RegistrationFieldSpecs = {
  [K in RegistrationFieldKey]: {
    label: string;
    subtitle: string;
    isParty: boolean;
    validate: (value: string) => string | false;
    toForm: (value: RegistrationFields[K]) => string;
    fromForm: (value: string) => RegistrationFields[K];
  };
};

const partyField = (label: string, subtitle: string) => ({
  label,
  subtitle,
  isParty: true,
  validate: (value: string) => validatePartyId(value.trim()),
  toForm: (value: string) => value,
  fromForm: (value: string) => value.trim(),
});

const weightField = (label: string, subtitle: string) => ({
  label,
  subtitle,
  isParty: false,
  validate: (value: string) => validateRequiredActivityWeight(value.trim()),
  toForm: normalizeDecimal,
  fromForm: normalizeDecimal,
});

export const registrationFields: RegistrationFieldSpecs = {
  paymaster: partyField(
    'Paymaster',
    "The party paying for the synchronizer's traffic on the decentralized synchronizer"
  ),
  maxActivityWeight: weightField(
    'Max Activity Weight',
    'Highest weight with which traffic paid for this synchronizer earns app activity. At least 0.'
  ),
  maxAssetActivityWeight: weightField(
    'Max Asset Activity Weight',
    'Highest weight with which traffic paid for this synchronizer earns asset activity. At least 0.'
  ),
};

export const registrationFieldKeys = Object.keys(registrationFields) as RegistrationFieldKey[];

export const toRegistrationFieldValues = (fields: RegistrationFields): RegistrationFieldValues =>
  Object.fromEntries(
    registrationFieldKeys.map(key => [key, registrationFields[key].toForm(fields[key])])
  ) as RegistrationFieldValues;

export const fromRegistrationFieldValues = (values: RegistrationFieldValues): RegistrationFields =>
  Object.fromEntries(
    registrationFieldKeys.map(key => [key, registrationFields[key].fromForm(values[key])])
  ) as RegistrationFields;

// Lists the fields the update changes, or all of them when the current registration is unknown.
export const registrationChanges = (
  current: RegistrationFields | undefined,
  proposed: RegistrationFields
): ConfigChange[] =>
  registrationFieldKeys
    .map(key => ({
      fieldName: key,
      label: registrationFields[key].label,
      currentValue: current ? registrationFields[key].toForm(current[key]) : '',
      newValue: registrationFields[key].toForm(proposed[key]),
      isId: registrationFields[key].isParty,
    }))
    .filter(change => change.currentValue !== change.newValue);

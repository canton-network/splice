// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useState } from 'react';
import { Option } from '../components/form-components/SelectField';
import {
  validatePartyId,
  validateRevokeFeaturedAppRight,
} from '../components/forms/formValidators';
import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

interface FeatureAppRightPicker {
  rightOptions: Option[];
  currentWeights: Record<string, string>;
  providerSearched: boolean;
  loadFeaturedAppRightsAndValidate: (value: string) => Promise<string | undefined>;
  validateRightSelection: (value: string) => string | false;
  resetOptions: () => void;
}

export const useFeaturedAppRightPicker = (
  svAdminClient: ReturnType<typeof useSvAdminClient>
): FeatureAppRightPicker => {
  const [rightOptions, setRightOptions] = useState<Option[]>([]);
  const [providerSearched, setProviderSearched] = useState(false);
  const [currentWeights, setCurrentWeights] = useState<Record<string, string>>({});

  const loadFeaturedAppRightsAndValidate = async (value: string) => {
    if (validatePartyId(value)) return undefined;

    try {
      const response = await svAdminClient.listFeaturedAppRightsByProvider(value);
      const options = response.featured_app_rights.map((contract: { contract_id: string }) => ({
        key: contract.contract_id,
        value: contract.contract_id,
      }));
      const weights = Object.fromEntries(
        response.featured_app_rights.map(c => {
          const aw = (c.payload as { activityWeight?: string | null }).activityWeight;
          return [c.contract_id, aw ?? 'None'];
        })
      );
      setRightOptions(options);
      setCurrentWeights(weights);
      setProviderSearched(true);
      return undefined;
    } catch {
      setRightOptions([]);
      setCurrentWeights({});
      setProviderSearched(false);
      return 'Could not load featured app rights for this provider';
    }
  };

  const validateRightSelection = (value: string): string | false => {
    const requiredError = validateRevokeFeaturedAppRight(value);
    if (requiredError) return requiredError;

    return rightOptions.some(option => option.value === value)
      ? false
      : 'Select a valid contract id';
  };

  const resetOptions = () => {
    setRightOptions([]);
    setCurrentWeights({});
    setProviderSearched(false);
  };

  return {
    rightOptions,
    currentWeights,
    providerSearched,
    loadFeaturedAppRightsAndValidate,
    validateRightSelection,
    resetOptions,
  };
};

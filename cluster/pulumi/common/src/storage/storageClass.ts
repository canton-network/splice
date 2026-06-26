// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { spliceConfig } from '../config/config';
import { hyperdiskSupportConfig } from '../config/hyperdiskSupportConfig';

export const standardStorageClassName = hyperdiskSupportConfig.hyperdiskSupport.enabled
  ? 'hyperdisk-standard-rwo'
  : 'standard-rwo';

export function persistentHeapDumpsPvc(): { size: string; volumeStorageClass: string } | undefined {
  return spliceConfig.configuration.persistentHeapDumps
    ? { size: '35Gi', volumeStorageClass: standardStorageClassName }
    : undefined;
}

export const infraStandardStorageClassName = hyperdiskSupportConfig.hyperdiskSupport.enabledForInfra
  ? 'hyperdisk-standard-rwo'
  : 'standard-rwo';

export const infraPremiumStorageClassName = hyperdiskSupportConfig.hyperdiskSupport.enabledForInfra
  ? 'hyperdisk-balanced-rwo'
  : 'premium-rwo';
export const pvcSuffix = hyperdiskSupportConfig.hyperdiskSupport.enabled ? 'hd-pvc' : 'pvc';

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { spliceConfig } from '../config/config';

export const standardStorageClassName = 'hyperdisk-standard-rwo';

export function persistentHeapDumpsPvc(): { size: string; volumeStorageClass: string } | undefined {
  return spliceConfig.configuration.persistentHeapDumps
    ? { size: '35Gi', volumeStorageClass: standardStorageClassName }
    : undefined;
}

export const infraStandardStorageClassName = 'hyperdisk-standard-rwo';

export const infraPremiumStorageClassName = 'hyperdisk-balanced-rwo';
export const pvcSuffix = 'hd-pvc';

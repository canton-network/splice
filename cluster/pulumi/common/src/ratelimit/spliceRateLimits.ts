// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import fs from 'fs';

import { getPathToPrivateConfigFile, loadJsonFromFile } from '../utils';
import { SpliceRateLimits, SpliceRateLimitsSchema } from './spliceRateLimitsSchema';

export const SPLICE_RATE_LIMITS_FILE = 'splice-rate-limits.json';

function loadSpliceRateLimits(): SpliceRateLimits | undefined {
  const file = getPathToPrivateConfigFile(SPLICE_RATE_LIMITS_FILE);
  if (!file || !fs.existsSync(file)) {
    return undefined;
  }
  return SpliceRateLimitsSchema.parse(loadJsonFromFile(file));
}

export function scanRateLimitEnvVars(
  appConfigPath: string = 'canton.scan-apps.scan-app'
): { name: string; value: string }[] {
  const scan = loadSpliceRateLimits()?.scan;
  return scan
    ? [
        {
          name: 'ADDITIONAL_CONFIG_SCAN_RATE_LIMITS',
          value: `${appConfigPath}.parameters.rate-limiting = ${JSON.stringify(scan)}\n`,
        },
      ]
    : [];
}

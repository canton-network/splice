// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import fs from 'fs';

import { getPathToPrivateConfigFile, loadJsonFromFile } from '../utils';
import { EnvVar, scanRateLimitEnvVarsFor, SpliceRateLimits } from './spliceRateLimitsConfig';

export const SPLICE_RATE_LIMITS_FILE = 'splice-rate-limits.json';

export function scanRateLimitEnvVars(): EnvVar[] {
  const file = getPathToPrivateConfigFile(SPLICE_RATE_LIMITS_FILE);
  if (!file || !fs.existsSync(file)) {
    return [];
  }
  const rateLimits: SpliceRateLimits | undefined = loadJsonFromFile(file);
  return scanRateLimitEnvVarsFor(rateLimits?.scan);
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export const SCAN_RATE_LIMITS_ENV_VAR = 'ADDITIONAL_CONFIG_SCAN_RATE_LIMITS';

export function scanRateLimitEnvVarsFor(
  rateLimits: unknown,
  appConfigPath: string = 'canton.scan-apps.scan-app'
): { name: string; value: string }[] {
  return rateLimits
    ? [
        {
          name: SCAN_RATE_LIMITS_ENV_VAR,
          value: `${appConfigPath}.parameters.rate-limiting = ${JSON.stringify(rateLimits)}`,
        },
      ]
    : [];
}

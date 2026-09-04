// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export const SCAN_RATE_LIMITS_ENV_VAR = 'ADDITIONAL_CONFIG_SCAN_RATE_LIMITS';

export interface EnvVar {
  name: string;
  value: string;
}

export interface SimpleRateLimit {
  enabled?: boolean;
  'rate-per-second'?: number;
  'sustained-rate-per-second'?: number;
  'sustained-window-seconds'?: number;
}

export interface PerClientIpRateLimit {
  enabled?: boolean;
  limit?: SimpleRateLimit;
  'max-attribute-values'?: number;
  'ip-overrides'?: Record<string, SimpleRateLimit>;
}

export interface ScanRateLimits {
  global?: SimpleRateLimit & { 'per-client-ip'?: PerClientIpRateLimit };
}

export interface SpliceRateLimits {
  scan?: ScanRateLimits;
}

export function scanRateLimitEnvVarsFor(
  rateLimits: ScanRateLimits | undefined,
  appConfigPath: string = 'canton.scan-apps.scan-app'
): EnvVar[] {
  return rateLimits
    ? [
        {
          name: SCAN_RATE_LIMITS_ENV_VAR,
          value: `${appConfigPath}.parameters.rate-limiting = ${JSON.stringify(rateLimits)}`,
        },
      ]
    : [];
}

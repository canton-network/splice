// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export const localRateLimitedHeader = 'x-local-rate-limit';

export const rateLimitResponseHeaders = [
  localRateLimitedHeader,
  // draft RFC headers enabled via enable_x_ratelimit_headers
  'x-ratelimit-limit',
  'x-ratelimit-remaining',
  'x-ratelimit-reset',
  // added by envoy itself on the local reply it generates when rate limiting
  'x-envoy-ratelimited',
];

export const envoyExternalAddressHeader = 'x-envoy-external-address';

/**
 * Overrides the (client-controlled) headers the apps extract the client IP for their per-client-IP
 * rate limiting from, so that only the non-spoofable header set by the Envoy sidecar is used.
 */
export function envoyClientIpHeaderEnvVar(appConfigPath: string): {
  name: string;
  value: string;
} {
  return {
    name: 'ADDITIONAL_CONFIG_CLIENT_IP_HEADERS',
    value: `${appConfigPath}.parameters.rate-limiting.client-ip-headers = ["${envoyExternalAddressHeader}"]\n`,
  };
}

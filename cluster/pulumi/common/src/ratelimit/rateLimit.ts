// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';

import { EndpointValidation, RateLimitEnvoyFilter } from './envoyRateLimiter';
import { ExternalRateLimit } from './rateLimitSchema';

/**
 * Makes the sidecar log every rate limited request, independently of whether access
 * logging is enabled cluster wide.
 */
function logRateLimitedRequests(namespace: string, app: string): k8s.apiextensions.CustomResource {
  return new k8s.apiextensions.CustomResource(`${namespace}-${app}-rate-limit-access-log`, {
    apiVersion: 'telemetry.istio.io/v1',
    kind: 'Telemetry',
    metadata: {
      name: `${app}-rate-limit-access-log`,
      namespace,
    },
    spec: {
      selector: {
        matchLabels: {
          app,
        },
      },
      accessLogging: [
        {
          // the default envoy provider, which uses the mesh-wide accessLogFormat
          providers: [{ name: 'envoy' }],
          // Rate limited requests are recognizable in the log by
          // response_code_details=local_rate_limited and response_flags containing RL.
          filter: { expression: 'response.code == 429' },
        },
      ],
    },
  });
}

export interface RateLimitOptions {
  endpointValidation?: EndpointValidation;
}

export function installRateLimits(
  namespace: string,
  app: string,
  appPort: number,
  rateLimit: ExternalRateLimit,
  options: RateLimitOptions = {}
): void {
  new RateLimitEnvoyFilter(`${app}-rate-limit`, {
    namespace: namespace,
    appLabel: app,
    inboundPort: appPort,
    globalLimits: rateLimit.globalLimits,
    globalPerIpLimits: rateLimit.globalPerIpLimits,
    rateLimits: rateLimit.rateLimits,
    endpointValidation: options.endpointValidation,
  });
  logRateLimitedRequests(namespace, app);
}

export function installSequencerRateLimits(
  namespace: string,
  sequencerApp: string,
  port: number,
  rateLimit: ExternalRateLimit
): void {
  installRateLimits(namespace, sequencerApp, port, rateLimit, {
    endpointValidation: 'grpc',
  });
}

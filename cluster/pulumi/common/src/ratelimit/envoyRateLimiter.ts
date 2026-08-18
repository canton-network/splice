// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

import { parseScanYamlEndpoints, parseTokenRegistrySpecEndpoints } from '../config/scanEndpoints';

interface Limits {
  maxTokens: number;
  tokensPerFill: number;
  fillInterval: string;
}

interface PerIpLimits extends Limits {
  overrides?: Record<string, { ips: string[] } & Limits>;
}

interface MatchedLimits extends Limits {
  type: 'limited';
  perIpLimits?: PerIpLimits;
}

interface Banned {
  type: 'banned';
}

interface Unlimited {
  type: 'unlimited';
}

type RateLimitConfig = MatchedLimits | Banned | Unlimited;

export interface PathPrefixInfo {
  pathPrefix: string;
  isBanned: boolean;
}

interface LocalLimits<L> {
  [pathPrefix: string]: LocalLimit<L>;
}

type LocalLimit<L> = {
  name: string;
} & L;

// This is arbitrary, but must not match any limit `name` used for an EnvoyFilter
// above. All existing manual YAML entries use 'client_ip' so this is the nicest
// migration away from always specifying that.
const clientIpEntryKey = 'client_ip';

interface RateLimitEnvoyFilterArgs extends PerEndpointLimits {
  namespace: string;

  appLabel: pulumi.Input<string>;

  inboundPort: pulumi.Input<number>;

  /**
   * Used when no descriptors match the request.
   * */
  globalLimits: Limits;
}

export interface PerEndpointLimits {
  // all the rate limits must be respected, there's an AND relationship between them
  rateLimits?: LocalLimits<RateLimitConfig>;
}

export function extractPathPrefixes(
  rateLimits?: PerEndpointLimits['rateLimits']
): PathPrefixInfo[] {
  if (!rateLimits) {
    return [];
  }

  return Object.entries(rateLimits)
    .map(([pathPrefix, rl]) => {
      const isBanned = rl.type === 'banned';
      return { pathPrefix, isBanned };
    })
    .filter(
      info => info.pathPrefix.startsWith('/api/scan') || info.pathPrefix.startsWith('/registry')
    );
}

function validateEndpointCoverage(
  scanEndpoints: string[],
  configuredScanPrefixes: string[]
): { missing: string[]; orphaned: string[] } {
  // Check for missing prefixes
  const missing = scanEndpoints.filter(
    endpoint => !configuredScanPrefixes.some(prefix => endpoint.startsWith(prefix))
  );

  // Check for orphaned prefixes
  const orphaned = configuredScanPrefixes.filter(
    prefix => !scanEndpoints.some(endpoint => endpoint.startsWith(prefix))
  );

  return { missing, orphaned };
}

export function validateIpLimits(pathPrefix: string, rateLimit: LocalLimit<MatchedLimits>): void {
  if (!rateLimit.perIpLimits) {
    return;
  }

  const seenIps = new Set<string>();
  const duplicates: string[] = [];

  Object.entries(rateLimit.perIpLimits.overrides || {}).forEach(([overrideKey, override]) => {
    override.ips.forEach(ip => {
      if (seenIps.has(ip)) {
        duplicates.push(`${ip} (in override '${overrideKey}')`);
      } else {
        seenIps.add(ip);
      }
    });
  });

  if (duplicates.length > 0) {
    throw new Error(`${pathPrefix}: duplicate IPs in per-IP rate limits: ${duplicates.join(', ')}`);
  }
}

function validateEffectiveRateLimits(
  args: RateLimitEnvoyFilterArgs
): LocalLimits<MatchedLimits> | undefined {
  const collidingPathNames = Object.entries(args.rateLimits || {})
    .filter(([, rl]) => rl.name === clientIpEntryKey)
    .map(([path]) => path);
  if (collidingPathNames.length > 0) {
    throw new Error(
      `${collidingPathNames.join(', ')} use reserved name ${clientIpEntryKey}; choose a different name`
    );
  }

  // Validate scan.yaml endpoint coverage
  const scanEndpoints = parseScanYamlEndpoints();

  const configuredScanPrefixes = Object.keys(args.rateLimits || {}).filter(pathPrefix =>
    pathPrefix.startsWith('/api/scan')
  );

  const { missing, orphaned } = validateEndpointCoverage(scanEndpoints, configuredScanPrefixes);

  const tokenRegistryEndpoints = parseTokenRegistrySpecEndpoints();

  const configuredRegistryPrefixes = Object.keys(args.rateLimits || {}).filter(pathPrefix =>
    pathPrefix.startsWith('/registry')
  );

  const registryValidation = validateEndpointCoverage(
    tokenRegistryEndpoints,
    configuredRegistryPrefixes
  );

  const totalMissing = missing.concat(registryValidation.missing);
  const totalOrphaned = orphaned.concat(registryValidation.orphaned);

  if (totalMissing.length > 0 || totalOrphaned.length > 0) {
    const errorParts: string[] = ['Rate limit configuration errors:'];
    if (totalMissing.length > 0) {
      errorParts.push(`- Missing rate limit prefixes for endpoints: ${totalMissing.join(', ')}`);
    }
    if (totalOrphaned.length > 0) {
      errorParts.push(
        `- Orphaned rate limit prefixes not matching any schema route: ${totalOrphaned.join(', ')}`
      );
    }
    throw new Error(errorParts.join('\n'));
  }

  // Filter out banned and unlimited entries
  const effectiveRateLimits = Object.fromEntries(
    Object.entries(args.rateLimits || {}).filter(
      (ent): ent is [string, LocalLimit<MatchedLimits>] => {
        // TODO (#4201): in banned case, implement actual banning with special short-circuit for whitelisted IPs
        // Currently skipping banned endpoints instead of setting 0/0 limits
        // in unlimited case, we fall back to globalRateLimit so don't need a rule
        const [, rl] = ent;
        return rl.type === 'limited';
      }
    )
  );

  Object.entries(effectiveRateLimits).forEach(([pathPrefix, rateLimit]) => {
    validateIpLimits(pathPrefix, rateLimit);
  });

  return effectiveRateLimits;
}

export function buildRateLimitActions(effectiveRateLimits: LocalLimits<MatchedLimits>): unknown[] {
  return Object.entries(effectiveRateLimits).flatMap(([pathPrefix, rateLimit]) => {
    const actions = [];

    // Action 1: generate the per-endpoint action
    const baseAction = {
      header_value_match: {
        descriptor_value: rateLimit.name,
        expect_match: true,
        headers: [
          {
            name: ':path',
            string_match: {
              prefix: pathPrefix,
              ignore_case: true,
            },
          },
        ],
      },
    };

    actions.push({ actions: [baseAction] });

    // Action 2: generate the per-IP action if perIpLimits exists
    if (rateLimit.perIpLimits) {
      actions.push({
        actions: [
          baseAction,
          {
            request_headers: {
              descriptor_key: clientIpEntryKey,
              header_name: 'x-forwarded-for',
            },
          },
        ],
      });
    }

    return actions;
  });
}

export function buildRateLimitDescriptors(
  effectiveRateLimits: LocalLimits<MatchedLimits>
): unknown[] {
  return Object.values(effectiveRateLimits).flatMap(rateLimit => {
    const descs = [];

    // per-endpoint bucket
    descs.push({
      entries: [{ key: 'header_match', value: rateLimit.name }],
      token_bucket: {
        max_tokens: rateLimit.maxTokens,
        tokens_per_fill: rateLimit.tokensPerFill,
        fill_interval: rateLimit.fillInterval,
      },
    });

    // generate the per-IP buckets if configured
    if (rateLimit.perIpLimits) {
      // IP-specific overrides first, so they take precedence over the generic per-IP bucket
      Object.entries(rateLimit.perIpLimits.overrides || {}).forEach(([, override]) => {
        override.ips.forEach(ip => {
          descs.push({
            entries: [
              { key: 'header_match', value: rateLimit.name },
              { key: clientIpEntryKey, value: ip },
            ],
            token_bucket: {
              max_tokens: override.maxTokens,
              tokens_per_fill: override.tokensPerFill,
              fill_interval: override.fillInterval,
            },
          });
        });
      });

      // Generic per-IP fallback last
      descs.push({
        entries: [{ key: 'header_match', value: rateLimit.name }, { key: clientIpEntryKey }],
        token_bucket: {
          max_tokens: rateLimit.perIpLimits.maxTokens,
          tokens_per_fill: rateLimit.perIpLimits.tokensPerFill,
          fill_interval: rateLimit.perIpLimits.fillInterval,
        },
      });
    }

    return descs;
  });
}

export class RateLimitEnvoyFilter extends pulumi.ComponentResource {
  public readonly envoyFilter: k8s.apiextensions.CustomResource;

  constructor(
    name: string,
    args: RateLimitEnvoyFilterArgs,
    opts?: pulumi.ComponentResourceOptions
  ) {
    super('splice:RateLimit', `splice-${args.namespace}-${name}`, args, opts);
    const effectiveRateLimits = validateEffectiveRateLimits(args);

    const rateLimitActions = buildRateLimitActions(effectiveRateLimits || {});

    this.envoyFilter = new k8s.apiextensions.CustomResource(
      `${args.namespace}-${name}`,
      {
        apiVersion: 'networking.istio.io/v1alpha3',
        kind: 'EnvoyFilter',
        metadata: {
          name: name,
          namespace: args.namespace,
        },
        spec: {
          workloadSelector: {
            labels: {
              app: args.appLabel,
            },
          },
          configPatches: [
            // Patch 1: Add the rate limit filter to the HTTP filter chain.
            {
              applyTo: 'HTTP_FILTER',
              match: {
                context: 'SIDECAR_INBOUND',
                listener: {
                  filterChain: {
                    filter: {
                      name: 'envoy.filters.network.http_connection_manager',
                    },
                  },
                },
              },
              patch: {
                operation: 'INSERT_BEFORE',
                value: {
                  name: 'envoy.filters.http.local_ratelimit',
                  typed_config: {
                    '@type': 'type.googleapis.com/udpa.type.v1.TypedStruct',
                    type_url:
                      'type.googleapis.com/envoy.extensions.filters.http.local_ratelimit.v3.LocalRateLimit',
                    value: {
                      stat_prefix: 'http_local_rate_limiter',
                    },
                  },
                },
              },
            },
            // Patch 2: Configure the rate limiting rules on the HTTP route.
            {
              applyTo: 'HTTP_ROUTE',
              match: {
                context: 'SIDECAR_INBOUND',
                routeConfiguration: {
                  vhost: {
                    name: pulumi.interpolate`inbound|http|${args.inboundPort}`,
                    route: { action: 'ANY' },
                  },
                },
              },
              patch: {
                operation: 'MERGE',
                value: {
                  route: {
                    rate_limits: rateLimitActions,
                  },
                  typed_per_filter_config: {
                    'envoy.filters.http.local_ratelimit': {
                      '@type':
                        'type.googleapis.com/envoy.extensions.filters.http.local_ratelimit.v3.LocalRateLimit',
                      stat_prefix: 'http_local_rate_limiter',
                      token_bucket: {
                        max_tokens: args.globalLimits.maxTokens,
                        tokens_per_fill: args.globalLimits.tokensPerFill,
                        fill_interval: args.globalLimits.fillInterval,
                      },
                      filter_enabled: {
                        runtime_key: 'local_rate_limit_enabled',
                        default_value: {
                          numerator: 100,
                          denominator: 'HUNDRED',
                        },
                      },
                      filter_enforced: {
                        runtime_key: 'local_rate_limit_enforced',
                        default_value: {
                          numerator: 100,
                          denominator: 'HUNDRED',
                        },
                      },
                      response_headers_to_add: [
                        {
                          append_action: 'OVERWRITE_IF_EXISTS_OR_ADD',
                          header: {
                            key: 'x-local-rate-limit',
                            value: 'true',
                          },
                        },
                      ],
                      // simplified descriptors by combining with actions and requiring all the tokens of an action to be set
                      // a descriptor in practice is a subset of tags from a rate limit
                      // but important to note that for each rate limit only one descriptor can match, if multiple descriptors match, the first one is used
                      descriptors: buildRateLimitDescriptors(effectiveRateLimits || {}),
                    },
                  },
                },
              },
            },
          ],
        },
      },
      { parent: this }
    );

    this.registerOutputs({
      envoyFilter: this.envoyFilter,
    });
  }
}

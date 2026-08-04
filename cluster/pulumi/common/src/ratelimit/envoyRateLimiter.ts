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

interface PerCidrLimits extends Limits {
  overrides?: Record<string, { cidrs: string[] } & Limits>;
}

interface MatchedLimits extends Limits {
  type: 'limited';
  perIpLimits?: PerIpLimits;
  perCidrLimits?: PerCidrLimits;
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

// Envoy native descriptor key for CIDR-based rate limiting using
// remote_address_match. The action checks the connection's remote address
// against configured CIDR ranges and produces this descriptor entry.
const remoteAddressMatchKey = 'remote_address_match';
const remoteAddressMatchDefaultValue = 'per-cidr-default';

function cidrToEnvoyCidrRange(cidr: string): {
  address_prefix: string;
  prefix_len: { value: number };
} {
  const [network, prefixStr] = cidr.split('/');
  return {
    address_prefix: network,
    prefix_len: { value: parseInt(prefixStr, 10) },
  };
}

function ipToLong(ip: string): number {
  return ip.split('.').reduce((acc, octet) => (acc << 8) + parseInt(octet, 10), 0) >>> 0;
}

function parseCidr(cidr: string): { networkLong: number; prefix: number; broadcastLong: number } {
  const [network, prefixStr] = cidr.split('/');
  const prefix = parseInt(prefixStr, 10);
  const networkLong = ipToLong(network);
  const hostBits = 32 - prefix;
  const hostCount = 1 << hostBits;
  const networkMask = ~(hostCount - 1) >>> 0;
  const normalizedNetworkLong = networkLong & networkMask;
  const broadcastLong = normalizedNetworkLong | ((hostCount - 1) >>> 0);
  return { networkLong: normalizedNetworkLong, prefix, broadcastLong };
}

function cidrsOverlap(a: string, b: string): boolean {
  const parsedA = parseCidr(a);
  const parsedB = parseCidr(b);
  return (
    (parsedA.networkLong <= parsedB.networkLong && parsedB.networkLong <= parsedA.broadcastLong) ||
    (parsedB.networkLong <= parsedA.networkLong && parsedA.networkLong <= parsedB.broadcastLong)
  );
}

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

export function validateCidrLimits(pathPrefix: string, rateLimit: LocalLimit<MatchedLimits>): void {
  if (!rateLimit.perCidrLimits) {
    return;
  }

  const cidrs: { cidr: string; overrideKey: string }[] = [];

  Object.entries(rateLimit.perCidrLimits.overrides || {}).forEach(([overrideKey, override]) => {
    override.cidrs.forEach(cidr => {
      cidrs.push({ cidr, overrideKey });
    });
  });

  const overlaps: string[] = [];
  for (let i = 0; i < cidrs.length; i++) {
    for (let j = i + 1; j < cidrs.length; j++) {
      if (cidrsOverlap(cidrs[i].cidr, cidrs[j].cidr)) {
        overlaps.push(
          `${cidrs[i].cidr} (in override '${cidrs[i].overrideKey}') and ${cidrs[j].cidr} (in override '${cidrs[j].overrideKey}')`
        );
      }
    }
  }

  if (overlaps.length > 0) {
    throw new Error(
      `${pathPrefix}: overlapping CIDRs in per-CIDR rate limits: ${overlaps.join(', ')}`
    );
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
    validateCidrLimits(pathPrefix, rateLimit);
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

    // Action 3: generate the per-CIDR actions if perCidrLimits exists.
    // Each override CIDR gets its own action so it can use a distinct token bucket.
    // A final catch-all action matches any IP not covered by an override and uses the
    // generic per-CIDR token bucket.
    if (rateLimit.perCidrLimits) {
      const overrideCidrRanges: { address_prefix: string; prefix_len: { value: number } }[] = [];

      Object.entries(rateLimit.perCidrLimits.overrides || {}).forEach(([, override]) => {
        override.cidrs.forEach(cidr => {
          overrideCidrRanges.push(cidrToEnvoyCidrRange(cidr));
          actions.push({
            actions: [
              baseAction,
              {
                remote_address_match: {
                  descriptor_value: cidr,
                  address_matcher: {
                    cidr_ranges: [cidrToEnvoyCidrRange(cidr)],
                  },
                },
              },
            ],
          });
        });
      });

      // Catch-all action for the generic per-CIDR bucket. invert_match ensures that IPs
      // already covered by an override CIDR produce a different descriptor and therefore
      // consume only the override bucket, not the generic one.
      actions.push({
        actions: [
          baseAction,
          {
            remote_address_match: {
              descriptor_value: remoteAddressMatchDefaultValue,
              address_matcher: {
                cidr_ranges: overrideCidrRanges,
                invert_match: true,
              },
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

    // generate the per-CIDR buckets if configured.
    // These come after per-IP buckets so explicit per-IP overrides take precedence.
    if (rateLimit.perCidrLimits) {
      // CIDR-specific overrides first
      Object.entries(rateLimit.perCidrLimits.overrides || {}).forEach(([, override]) => {
        override.cidrs.forEach(cidr => {
          descs.push({
            entries: [
              { key: 'header_match', value: rateLimit.name },
              { key: remoteAddressMatchKey, value: cidr },
            ],
            token_bucket: {
              max_tokens: override.maxTokens,
              tokens_per_fill: override.tokensPerFill,
              fill_interval: override.fillInterval,
            },
          });
        });
      });

      // Generic per-CIDR fallback
      descs.push({
        entries: [
          { key: 'header_match', value: rateLimit.name },
          { key: remoteAddressMatchKey, value: remoteAddressMatchDefaultValue },
        ],
        token_bucket: {
          max_tokens: rateLimit.perCidrLimits.maxTokens,
          tokens_per_fill: rateLimit.perCidrLimits.tokensPerFill,
          fill_interval: rateLimit.perCidrLimits.fillInterval,
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

    const enableEnvoyRateLimitMetricsAnnotation = `
proxyStatsMatcher:
  inclusionRegexps:
  - ".*http_local_rate_limit.*"
`.trim();

    this.envoyFilter = new k8s.apiextensions.CustomResource(
      `${args.namespace}-${name}`,
      {
        apiVersion: 'networking.istio.io/v1alpha3',
        kind: 'EnvoyFilter',
        metadata: {
          name: name,
          namespace: args.namespace,
          annotations: {
            'proxy.istio.io/config': enableEnvoyRateLimitMetricsAnnotation,
          },
        },
        spec: {
          workloadSelector: {
            labels: {
              app: args.appLabel,
            },
          },
          configPatches: [
            // Patch 1: Add the rate limit filter to the HTTP filter chain.
            // It is inserted at the beginning of the HTTP filter chain so that
            // rate limiting happens early.
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

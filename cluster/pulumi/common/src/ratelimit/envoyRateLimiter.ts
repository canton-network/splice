// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

import { parseScanYamlEndpoints, parseTokenRegistrySpecEndpoints } from '../config/scanEndpoints';
import { localRateLimitedHeader } from './rateLimitHeaders';

interface Limits {
  maxTokens: number;
  tokensPerFill: number;
  fillInterval: string;
}

interface PerIpRangeLimit extends Limits {
  // All IP ranges (CIDRs) listed under a single override key share one token
  // bucket. The combined request rate from every IP range in the override is
  // limited together.
  overrides?: Record<string, { ipRanges: string[] } & Limits>;
}

interface MatchedLimits extends Limits {
  type: 'limited';
  perIpRangeLimit?: PerIpRangeLimit;
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

// We intentionally do not use the x-forwarded-for header / client_ip descriptor
// for per-IP rate limiting. In our Istio configuration x-forwarded-for can be
// spoofed by the client, so we rely on Envoy's remote_address_match action
// instead, which checks the connection's remote address against configured IP
// ranges and produces this descriptor entry.
const remoteAddressMatchKey = 'remote_address_match';
const remoteAddressMatchDefaultValue = 'per-ip-range-default';

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

  // A /prefix leaves (32 - prefix) bits for the host portion of the address.
  // For example, /24 leaves 8 host bits, so the network contains 2^8 = 256 addresses.
  const hostBits = 32 - prefix;
  const hostCount = 2 ** hostBits;

  // Build a mask that keeps the network bits and clears the host bits.
  // hostCount - 1 is a value whose lower hostBits bits are all 1s.
  // Negating it gives 1s in the network-bit positions and 0s in the host-bit positions.
  // The `>>> 0` forces the result into an unsigned 32-bit integer, because JavaScript
  // bitwise operators otherwise work with signed 32-bit values.
  const networkMask = ~(hostCount - 1) >>> 0;

  // Normalize the network address by clearing the host bits. This also ensures the
  // user-supplied network address is rounded down to the real network start
  // (e.g. 192.168.1.5/24 becomes 192.168.1.0/24).
  const normalizedNetworkLong = (networkLong & networkMask) >>> 0;

  // The broadcast address is the last address in the network: set all host bits to 1.
  // We OR the normalized network address with the host portion (hostCount - 1).
  // Both intermediate results and the final result are forced unsigned with `>>> 0`,
  // otherwise JavaScript's `|` operator can produce negative numbers for values >= 2^31.
  const broadcastLong = (normalizedNetworkLong | ((hostCount - 1) >>> 0)) >>> 0;

  return { networkLong: normalizedNetworkLong, prefix, broadcastLong };
}

function cidrsOverlap(a: string, b: string): boolean {
  const parsedA = parseCidr(a);
  const parsedB = parseCidr(b);
  // Two CIDRs overlap iff the start of one range falls inside the other range.
  // Because each parsed CIDR is a contiguous [networkLong, broadcastLong] interval,
  // checking whether either network address lies within the other's interval is enough.
  return (
    (parsedA.networkLong <= parsedB.networkLong && parsedB.networkLong <= parsedA.broadcastLong) ||
    (parsedB.networkLong <= parsedA.networkLong && parsedA.networkLong <= parsedB.broadcastLong)
  );
}

const reservedEntryKeys = [remoteAddressMatchKey, 'client_ip'];

// The per-IP descriptors are wildcard descriptors: envoy allocates a token bucket per
// observed client IP and keeps them in an LRU cache whose size defaults to a mere 20
// entries.
export const maxDynamicDescriptorsPerLimit = 10000;

// Envoy rejects token buckets refilling faster than this, and requires every descriptor
// fill interval to be a multiple of the global (default) bucket's fill interval.
const minFillIntervalMs = 50;

// uint32 in envoy's TokenBucket proto
const maxTokenValue = 4294967295;

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

export function validateIpRangeLimits(
  pathPrefix: string,
  rateLimit: LocalLimit<MatchedLimits>
): void {
  if (!rateLimit.perIpRangeLimit) {
    return;
  }

  const overrideKeys = Object.keys(rateLimit.perIpRangeLimit.overrides || {});
  const reservedKeyUsages = overrideKeys.filter(key => key === remoteAddressMatchDefaultValue);
  if (reservedKeyUsages.length > 0) {
    throw new Error(
      `${pathPrefix}: override key '${remoteAddressMatchDefaultValue}' is reserved for the generic per-IP-range fallback bucket`
    );
  }

  const ipRanges: { ipRange: string; overrideKey: string }[] = [];

  overrideKeys.forEach(overrideKey => {
    const override = rateLimit.perIpRangeLimit!.overrides![overrideKey];
    override.ipRanges.forEach(ipRange => {
      ipRanges.push({ ipRange, overrideKey });
    });
  });

  const overlaps: string[] = [];
  ipRanges.forEach((left, leftIndex) => {
    ipRanges.slice(leftIndex + 1).forEach(right => {
      if (cidrsOverlap(left.ipRange, right.ipRange)) {
        overlaps.push(
          `${left.ipRange} (in override '${left.overrideKey}') and ${right.ipRange} (in override '${right.overrideKey}')`
        );
      }
    });
  });

  if (overlaps.length > 0) {
    throw new Error(
      `${pathPrefix}: overlapping IP ranges in per-IP-range rate limits: ${overlaps.join(', ')}`
    );
  }
}

/**
 * Parses a protobuf duration (as accepted by envoy's `fill_interval`) into milliseconds.
 */
export function parseFillIntervalMs(fillInterval: string, context: string): number {
  const match = /^(\d+(?:\.\d+)?)s$/.exec(fillInterval);
  if (!match) {
    throw new Error(
      `${context}: invalid fillInterval '${fillInterval}', expected a duration in seconds such as '60s'`
    );
  }
  return Math.round(parseFloat(match[1]) * 1000);
}

function validateLimits(context: string, limits: Limits, globalFillIntervalMs?: number): void {
  const fillIntervalMs = parseFillIntervalMs(limits.fillInterval, context);
  // envoy rejects fill intervals below 50ms, see the local rate limit filter docs.
  // https://www.envoyproxy.io/docs/envoy/latest/api-v3/extensions/filters/http/local_ratelimit/v3/local_rate_limit.proto#envoy-v3-api-field-extensions-filters-http-local-ratelimit-v3-localratelimit-token-bucket
  if (fillIntervalMs < minFillIntervalMs) {
    throw new Error(
      `${context}: fillInterval '${limits.fillInterval}' is below the ${minFillIntervalMs}ms minimum enforced by envoy`
    );
  }
  // envoy requires descriptor fill intervals to be a multiple of the default bucket's
  // fill interval; violating this makes istiod push a config that envoy NACKs, which
  // silently leaves the sidecar running without any rate limits at all.
  // https://www.envoyproxy.io/docs/envoy/latest/api-v3/extensions/filters/http/local_ratelimit/v3/local_rate_limit.proto#envoy-v3-api-field-extensions-filters-http-local-ratelimit-v3-localratelimit-descriptors
  if (globalFillIntervalMs !== undefined && fillIntervalMs % globalFillIntervalMs !== 0) {
    throw new Error(
      `${context}: fillInterval '${limits.fillInterval}' must be a multiple of the globalLimits fillInterval (${globalFillIntervalMs}ms)`
    );
  }
  [['maxTokens', limits.maxTokens] as const, ['tokensPerFill', limits.tokensPerFill] as const]
    .filter(([, value]) => !Number.isInteger(value) || value < 0 || value > maxTokenValue)
    .forEach(([field, value]) => {
      throw new Error(
        `${context}: ${field} must be an integer in [0, ${maxTokenValue}], got ${value}`
      );
    });
}

export function validateTokenBuckets(
  globalLimits: Limits,
  effectiveRateLimits: LocalLimits<MatchedLimits>
): void {
  validateLimits('globalLimits', globalLimits);
  const globalFillIntervalMs = parseFillIntervalMs(globalLimits.fillInterval, 'globalLimits');
  Object.entries(effectiveRateLimits).forEach(([pathPrefix, rateLimit]) => {
    validateLimits(pathPrefix, rateLimit, globalFillIntervalMs);
    if (rateLimit.perIpRangeLimit) {
      validateLimits(
        `${pathPrefix} perIpRangeLimit`,
        rateLimit.perIpRangeLimit,
        globalFillIntervalMs
      );
      Object.entries(rateLimit.perIpRangeLimit.overrides || {}).forEach(([name, override]) =>
        validateLimits(
          `${pathPrefix} perIpRangeLimit override '${name}'`,
          override,
          globalFillIntervalMs
        )
      );
    }
  });
}

function validateEffectiveRateLimits(
  args: RateLimitEnvoyFilterArgs
): LocalLimits<MatchedLimits> | undefined {
  const collidingPathNames = Object.entries(args.rateLimits || {})
    .filter(([, rl]) => reservedEntryKeys.includes(rl.name))
    .map(([path]) => path);
  if (collidingPathNames.length > 0) {
    throw new Error(
      `${collidingPathNames.join(', ')} use reserved name ${reservedEntryKeys.join('/')}; choose a different name`
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
    validateIpRangeLimits(pathPrefix, rateLimit);
  });

  validateTokenBuckets(args.globalLimits, effectiveRateLimits);

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

    // Action 2: generate the per-IP-range actions if perIpRangeLimit exists.
    // All IP ranges (CIDRs) within a single override share one token bucket,
    // identified by the override key. A final catch-all action matches any IP not
    // covered by an override and uses the generic per-IP-range token bucket.
    if (rateLimit.perIpRangeLimit) {
      const overrideIpRanges: { address_prefix: string; prefix_len: { value: number } }[] = [];

      Object.entries(rateLimit.perIpRangeLimit.overrides || {}).forEach(
        ([overrideKey, override]) => {
          const ipRangeMatchers = override.ipRanges.map(cidrToEnvoyCidrRange);
          overrideIpRanges.push(...ipRangeMatchers);
          actions.push({
            actions: [
              baseAction,
              {
                remote_address_match: {
                  descriptor_value: overrideKey,
                  address_matcher: {
                    cidr_ranges: ipRangeMatchers,
                  },
                },
              },
            ],
          });
        }
      );

      // Catch-all action for the generic per-IP-range bucket. invert_match ensures that
      // IPs already covered by an override IP range produce a different descriptor and
      // therefore consume only the override bucket, not the generic one.
      actions.push({
        actions: [
          baseAction,
          {
            remote_address_match: {
              descriptor_value: remoteAddressMatchDefaultValue,
              address_matcher: {
                cidr_ranges: overrideIpRanges,
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

    // generate the per-IP-range buckets if configured.
    // All IP ranges listed under a single override key share the same token bucket; the
    // combined traffic from all those IP ranges is rate-limited as one group.
    if (rateLimit.perIpRangeLimit) {
      // IP-range-specific overrides first. The override key identifies the shared bucket.
      Object.entries(rateLimit.perIpRangeLimit.overrides || {}).forEach(
        ([overrideKey, override]) => {
          descs.push({
            entries: [
              { key: 'header_match', value: rateLimit.name },
              { key: remoteAddressMatchKey, value: overrideKey },
            ],
            token_bucket: {
              max_tokens: override.maxTokens,
              tokens_per_fill: override.tokensPerFill,
              fill_interval: override.fillInterval,
            },
          });
        }
      );

      // Generic per-IP-range fallback for IPs not covered by any override IP range.
      descs.push({
        entries: [
          { key: 'header_match', value: rateLimit.name },
          { key: remoteAddressMatchKey, value: remoteAddressMatchDefaultValue },
        ],
        token_bucket: {
          max_tokens: rateLimit.perIpRangeLimit.maxTokens,
          tokens_per_fill: rateLimit.perIpRangeLimit.tokensPerFill,
          fill_interval: rateLimit.perIpRangeLimit.fillInterval,
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
                            key: localRateLimitedHeader,
                            value: 'true',
                          },
                        },
                      ],
                      // Emit X-RateLimit-Limit/Remaining/Reset.
                      // used in sidecar access logs
                      // not sent to the client, because we strip them on the ingress gateway
                      enable_x_ratelimit_headers: 'DRAFT_VERSION_03',
                      max_dynamic_descriptors: maxDynamicDescriptorsPerLimit,
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

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

import { localRateLimitedHeader } from './rateLimitHeaders';

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

// The descriptor entry key emitted by envoy's `masked_remote_address` rate limit action.
// This is hardcoded in envoy (see MaskedRemoteAddressAction::populateDescriptor) and
// cannot be configured, so the descriptors we generate must use exactly this key.
const clientIpEntryKey = 'masked_remote_address';
const reservedEntryKeys = [clientIpEntryKey, 'client_ip'];

// The per-IP descriptors are wildcard descriptors: envoy allocates a token bucket per
// observed client IP and keeps them in an LRU cache whose size defaults to a mere 20
// entries.
export const maxDynamicDescriptorsPerLimit = 10000;

// Envoy rejects token buckets refilling faster than this, and requires every descriptor
// fill interval to be a multiple of the global (default) bucket's fill interval.
const minFillIntervalMs = 50;

// uint32 in envoy's TokenBucket proto
const maxTokenValue = 4294967295;

export interface RateLimitEnvoyFilterArgs extends PerEndpointLimits {
  namespace: string;

  appLabel: pulumi.Input<string>;

  inboundPort: pulumi.Input<number>;

  globalLimits: Limits;

  globalPerIpLimits?: Limits;
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
  effectiveRateLimits: LocalLimits<MatchedLimits>,
  globalPerIpLimits?: Limits
): void {
  validateLimits('globalLimits', globalLimits);
  const globalFillIntervalMs = parseFillIntervalMs(globalLimits.fillInterval, 'globalLimits');
  if (globalPerIpLimits) {
    validateLimits('globalPerIpLimits', globalPerIpLimits, globalFillIntervalMs);
  }
  Object.entries(effectiveRateLimits).forEach(([pathPrefix, rateLimit]) => {
    validateLimits(pathPrefix, rateLimit, globalFillIntervalMs);
    if (rateLimit.perIpLimits) {
      validateLimits(`${pathPrefix} perIpLimits`, rateLimit.perIpLimits, globalFillIntervalMs);
      Object.entries(rateLimit.perIpLimits.overrides || {}).forEach(([name, override]) =>
        validateLimits(
          `${pathPrefix} perIpLimits override '${name}'`,
          override,
          globalFillIntervalMs
        )
      );
    }
  });
}

export function validateEffectiveRateLimits(
  args: RateLimitEnvoyFilterArgs
): LocalLimits<MatchedLimits> {
  const collidingPathNames = Object.entries(args.rateLimits || {})
    .filter(([, rl]) => reservedEntryKeys.includes(rl.name))
    .map(([path]) => path);
  if (collidingPathNames.length > 0) {
    throw new Error(
      `${collidingPathNames.join(', ')} use reserved name ${reservedEntryKeys.join('/')}; choose a different name`
    );
  }

  // Filter out banned and unlimited entries
  const effectiveRateLimits = Object.fromEntries(
    Object.entries(args.rateLimits || {}).filter(
      (ent): ent is [string, LocalLimit<MatchedLimits>] => {
        // TODO (#4201): in banned case, implement actual banning with special short-circuit for whitelisted IPs
        // Currently skipping banned endpoints instead of setting 0/0 limits
        // in unlimited case, we fall back to the global limits so no descriptor is needed
        const [, rl] = ent;
        return rl.type === 'limited';
      }
    )
  );

  Object.entries(effectiveRateLimits).forEach(([pathPrefix, rateLimit]) => {
    validateIpLimits(pathPrefix, rateLimit);
  });

  validateTokenBuckets(args.globalLimits, effectiveRateLimits, args.globalPerIpLimits);

  return effectiveRateLimits;
}

export function clientIpDescriptorValue(ip: string): string {
  return `${ip}/32`;
}

// We deliberately do not key on the raw x-forwarded-for header: clients can
// prepend arbitrary entries to it, and our gateway only appends to it, so
// the header value is attacker controlled and per-IP limits could be evaded
// by simply varying the header on every request.
// masked_remote_address instead uses the address envoy trusts, which for the
// sidecar is the last x-forwarded-for hop, i.e. the one appended by our own
// ingress gateway.
const maskedRemoteAddressAction = {
  masked_remote_address: {
    // one bucket per client address
    v4_prefix_mask_len: 32,
    // the default of 0 would put all IPv6 clients into a single bucket
    v6_prefix_mask_len: 128,
  },
};

export function buildGlobalPerIpRateLimitAction(): unknown {
  return { actions: [maskedRemoteAddressAction] };
}

export function buildGlobalPerIpRateLimitDescriptor(globalPerIpLimits: Limits): unknown {
  return {
    entries: [{ key: clientIpEntryKey }],
    token_bucket: {
      max_tokens: globalPerIpLimits.maxTokens,
      tokens_per_fill: globalPerIpLimits.tokensPerFill,
      fill_interval: globalPerIpLimits.fillInterval,
    },
  };
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
        actions: [baseAction, maskedRemoteAddressAction],
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
              { key: clientIpEntryKey, value: clientIpDescriptorValue(ip) },
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

    // The global per-IP action/descriptor come last so that the more specific per-endpoint
    // descriptors are matched first.
    const rateLimitActions = buildRateLimitActions(effectiveRateLimits).concat(
      args.globalPerIpLimits ? [buildGlobalPerIpRateLimitAction()] : []
    );
    const rateLimitDescriptors = buildRateLimitDescriptors(effectiveRateLimits).concat(
      args.globalPerIpLimits ? [buildGlobalPerIpRateLimitDescriptor(args.globalPerIpLimits)] : []
    );

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
                      descriptors: rateLimitDescriptors,
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

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// k6 cannot import from `node_modules` by name, so js-yaml is imported by path.
// @ts-expect-error -- the bundle ships no type declarations
import { load } from "../node_modules/js-yaml/dist/js-yaml.mjs";

/**
 * Reads a cluster config (e.g. `cluster/deployment/<cluster>/config.resolved.yaml`) and returns
 * the endpoints flagged with `test: true`, see `scan.example.yaml` for the shape.
 */

interface Bucket {
  maxTokens: number;
  tokensPerFill: number;
  /** e.g. '60s', '10s', '1m' */
  fillInterval: string;
}

export interface ResolvedBucket extends Bucket {
  fillIntervalSeconds: number;
  /** sustained rate the bucket allows, in req/s */
  sustainedRps: number;
}

interface RateLimitEntry extends Partial<Bucket> {
  test?: boolean;
  name?: string;
  type?: "limited" | "unlimited" | string;
  perIpLimits?: Bucket;
}

interface ExternalRateLimits {
  globalLimits?: Bucket;
  globalPerIpLimits?: Bucket;
  rateLimits?: Record<string, RateLimitEntry>;
}

/** An endpoint flagged with `test: true`, with all the buckets that apply to it. */
export interface Endpoint {
  /** `name` of the entry, used to tag metrics and name scenarios */
  name: string;
  type: string;
  /** the endpoint's own bucket, shared by all clients */
  endpointLimits?: ResolvedBucket;
  /** the endpoint's bucket for a single client IP */
  endpointPerIpLimits?: ResolvedBucket;
  /** the bucket for a single client IP, shared by all endpoints of the service */
  globalPerIpLimits?: ResolvedBucket;
  /** the bucket shared by all clients and all endpoints of the service */
  globalLimits?: ResolvedBucket;
  /** true if the path is a gRPC service rather than an HTTP route */
  grpc: boolean;
  /** the URL under test, built from the domain passed to main.ts */
  url: string;
}

export function parseDurationSeconds(duration: string): number {
  const match = /^(\d+(?:\.\d+)?)(ms|s|m|h)?$/.exec(String(duration).trim());
  if (!match) {
    throw new Error(`cannot parse duration '${duration}'`);
  }
  const value = Number(match[1]);
  switch (match[2] ?? "s") {
    case "ms":
      return value / 1000;
    case "m":
      return value * 60;
    case "h":
      return value * 3600;
    default:
      return value;
  }
}

function resolveBucket(bucket?: Partial<Bucket>): ResolvedBucket | undefined {
  if (
    !bucket ||
    bucket.maxTokens === undefined ||
    bucket.tokensPerFill === undefined ||
    bucket.fillInterval === undefined
  ) {
    return undefined;
  }
  const fillIntervalSeconds = parseDurationSeconds(bucket.fillInterval);
  return {
    maxTokens: bucket.maxTokens,
    tokensPerFill: bucket.tokensPerFill,
    fillInterval: bucket.fillInterval,
    fillIntervalSeconds,
    sustainedRps: bucket.tokensPerFill / fillIntervalSeconds,
  };
}

/**
 * Whether the path is a gRPC one, e.g.
 * `/com.digitalasset.canton.sequencer.api.v30.SequencerConnectService/`. Such paths need a real
 * gRPC call, so the HTTP checks skip them.
 */
function isGrpcPath(path: string): boolean {
  return /^\/[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+\//.test(path);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Collects every `rateLimits` entry flagged with `test: true`, wherever its
 * `externalRateLimits` block sits in the config.
 */
export function collectTestedEndpoints(
  config: unknown,
  domain: string,
): Endpoint[] {
  const endpoints: Endpoint[] = [];

  const visit = (node: unknown, servicePath: string[]): void => {
    if (!isRecord(node)) {
      return;
    }
    for (const [key, value] of Object.entries(node)) {
      if (key === "externalRateLimits" && isRecord(value)) {
        collectFrom(value as ExternalRateLimits, servicePath.join("."));
      } else {
        visit(value, [...servicePath, key]);
      }
    }
  };

  const collectFrom = (limits: ExternalRateLimits, service: string): void => {
    const globalPerIpLimits = resolveBucket(limits.globalPerIpLimits);
    const globalLimits = resolveBucket(limits.globalLimits);
    for (const [path, entry] of Object.entries(limits.rateLimits ?? {})) {
      if (entry?.test !== true) {
        continue;
      }
      endpoints.push({
        name: entry.name ?? `${service}${path}`,
        type: entry.type ?? "limited",
        endpointLimits: resolveBucket(entry),
        endpointPerIpLimits: resolveBucket(entry.perIpLimits),
        globalPerIpLimits,
        globalLimits,
        grpc: isGrpcPath(path),
        url: `https://${domain}${path}`,
      });
    }
  };

  visit(config, []);
  return endpoints;
}

/** The per-IP bucket that rejects first, i.e. the stricter of the endpoint's and the global one. */
export function effectivePerIpBucket(
  endpoint: Endpoint,
): ResolvedBucket | undefined {
  const candidates = [
    endpoint.endpointPerIpLimits,
    endpoint.globalPerIpLimits,
  ].filter((b): b is ResolvedBucket => b !== undefined);
  if (candidates.length === 0) {
    return undefined;
  }
  return candidates.reduce((strictest, candidate) =>
    candidate.sustainedRps < strictest.sustainedRps ||
    (candidate.sustainedRps === strictest.sustainedRps &&
      candidate.maxTokens < strictest.maxTokens)
      ? candidate
      : strictest,
  );
}

/** The strictest bucket that is shared between clients, i.e. not keyed by client IP. */
export function effectiveSharedBucket(
  endpoint: Endpoint,
): ResolvedBucket | undefined {
  const candidates = [endpoint.endpointLimits, endpoint.globalLimits].filter(
    (b): b is ResolvedBucket => b !== undefined,
  );
  if (candidates.length === 0) {
    return undefined;
  }
  return candidates.reduce((a, b) => (b.sustainedRps < a.sustainedRps ? b : a));
}

export function loadConfig(configPath: string, domain: string): Endpoint[] {
  // `open` only exists in k6's init context and resolves relative paths against this file, while
  // the paths passed in are relative to the package root, so both are tried.
  const candidates = configPath.startsWith("/")
    ? [configPath]
    : [`../${configPath}`, configPath];
  for (const candidate of candidates) {
    try {
      return parseConfig(open(candidate), domain);
    } catch {
      // try the next candidate
    }
  }
  throw new Error(
    `cannot read config '${configPath}' (tried ${candidates.join(", ")})`,
  );
}

/** Parses a YAML cluster config and collects the endpoints to test in it. */
export function parseConfig(raw: string, domain: string): Endpoint[] {
  return collectTestedEndpoints(
    (load as (input: string) => unknown)(raw),
    domain,
  );
}

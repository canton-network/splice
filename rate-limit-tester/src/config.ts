// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// k6 cannot import from `node_modules` by name, so js-yaml is imported by path.
// @ts-expect-error -- the bundle ships no type declarations
import { load } from "../node_modules/js-yaml/dist/js-yaml.mjs";

/**
 * Reads a cluster config (e.g. `cluster/deployment/<cluster>/config.resolved.yaml`) and returns the
 * service wide rate limits declared in it, see `scan.example.yaml` for the shape. Per endpoint
 * buckets (`rateLimits`) are ignored: only the global and global per-IP ones are exercised, against
 * a probe path that has no bucket of its own.
 *
 * The config declares buckets but no hostnames, and each service is reached on a host of its own,
 * so the URLs are supplied per service by the caller (`SCAN_URL` / `SEQUENCER_URL`). A service
 * without a URL is not part of the run.
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

interface ExternalRateLimits {
  globalLimits?: Bucket;
  globalPerIpLimits?: Bucket;
}

/**
 * The protocol spoken on the rate limited port, which decides how a rejection is recognized: Envoy
 * answers a rate limited HTTP request with `429`, but a gRPC call with HTTP `200` and
 * `RESOURCE_EXHAUSTED` (see `rate_limited_as_resource_exhausted` in `cluster/pulumi/common/src`).
 */
export type Protocol = "http" | "grpc";

/** The gRPC status code (`RESOURCE_EXHAUSTED`) Envoy returns for a rate limited gRPC call. */
export const RATE_LIMITED_GRPC_STATUS = 8;

/**
 * How each service is probed, keyed by the last segment of the config path its `externalRateLimits`
 * block sits under (`sv.scan` -> `scan`). The paths have no per endpoint bucket of their own in
 * `cluster/configs/shared/rate-limits/`, so they are charged against the global buckets only. The
 * same keys identify the base URLs passed in, see `ServiceUrls`.
 */
const PROBES: Record<string, { protocol: Protocol; probePath: string }> = {
  scan: { protocol: "http", probePath: "/api/scan/version" },
  // The sequencer's public API (port 5008) is gRPC. GetTime is read only and, like every
  // SequencerService method, needs authentication, so the sequencer rejects it with UNAUTHENTICATED
  // before doing any work; the limit is enforced by the sidecar, before the app sees the call.
  sequencer: {
    protocol: "grpc",
    probePath:
      "/com.digitalasset.canton.sequencer.api.v30.SequencerService/GetTime",
  },
};

/**
 * Base URLs of the services under test, keyed like `PROBES`, e.g.
 * `{ scan: 'https://scan.sv-2.example.com' }`. A service missing from this map is skipped.
 */
export type ServiceUrls = Record<string, string>;

/** The key a config path maps to, i.e. its last segment (`sv.scan` -> `scan`). */
export function serviceKey(name: string): string {
  return name.split(".").pop() ?? "";
}

/** `https://host`, `https://host/` and a bare `host` all normalize to `https://host`. */
export function normalizeBaseUrl(url: string): string {
  const withScheme = /^https?:\/\//.test(url.trim())
    ? url.trim()
    : `https://${url.trim()}`;
  return withScheme.replace(/\/+$/, "");
}

/** How to probe the service a config path points at, if it is one this tester knows. */
export function probeFor(
  name: string,
): { protocol: Protocol; probePath: string } | undefined {
  return PROBES[serviceKey(name)];
}

/** A service whose global rate limits are under test. */
export interface Target {
  /** where the `externalRateLimits` block sits in the config, e.g. `sv.scan` */
  name: string;
  /** the bucket for a single client IP, shared by all endpoints of the service */
  globalPerIpLimits?: ResolvedBucket;
  /** the bucket shared by all clients and all endpoints of the service */
  globalLimits?: ResolvedBucket;
  /** the URL under test, built from this service's base URL and the probe path */
  url: string;
  /** the protocol the URL speaks, which decides how a rejection is recognized */
  protocol: Protocol;
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Collects every `externalRateLimits` block declaring a global bucket, wherever it sits in the
 * config. A service this tester cannot probe (missing from `PROBES`), or one with no base URL in
 * `serviceUrls`, is skipped: the wrong protocol, path or host would either miss the global buckets
 * or never reach the app at all. `probePathOverride` replaces the probe path of every service, see
 * `-e PROBE_PATH=`.
 */
export function collectTargets(
  config: unknown,
  serviceUrls: ServiceUrls,
  probePathOverride?: string,
): Target[] {
  const targets: Target[] = [];

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
    const probe = probeFor(service);
    const baseUrl = serviceUrls[serviceKey(service)];
    if ((!globalPerIpLimits && !globalLimits) || !probe || !baseUrl) {
      return;
    }
    targets.push({
      name: service,
      globalPerIpLimits,
      globalLimits,
      url: `${normalizeBaseUrl(baseUrl)}${probePathOverride ?? probe.probePath}`,
      protocol: probe.protocol,
    });
  };

  visit(config, []);
  return targets;
}

/** The per-IP bucket that applies to a target, i.e. the global per-IP one. */
export function perIpBucket(target: Target): ResolvedBucket | undefined {
  return target.globalPerIpLimits;
}

/** The bucket that is shared between clients, i.e. not keyed by client IP. */
export function sharedBucket(target: Target): ResolvedBucket | undefined {
  return target.globalLimits;
}

export function loadConfig(
  configPath: string,
  serviceUrls: ServiceUrls,
  probePathOverride?: string,
): Target[] {
  // `open` only exists in k6's init context and resolves relative paths against this file, while
  // the paths passed in are relative to the package root, so both are tried.
  const candidates = configPath.startsWith("/")
    ? [configPath]
    : [`../${configPath}`, configPath];
  for (const candidate of candidates) {
    try {
      return parseConfig(open(candidate), serviceUrls, probePathOverride);
    } catch {
      // try the next candidate
    }
  }
  throw new Error(
    `cannot read config '${configPath}' (tried ${candidates.join(", ")})`,
  );
}

/** Parses a YAML cluster config and collects the global rate limits declared in it. */
export function parseConfig(
  raw: string,
  serviceUrls: ServiceUrls,
  probePathOverride?: string,
): Target[] {
  return collectTargets(
    (load as (input: string) => unknown)(raw),
    serviceUrls,
    probePathOverride,
  );
}

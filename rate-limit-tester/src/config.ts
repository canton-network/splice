// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// k6 cannot import from `node_modules` by name, so js-yaml is imported by path.
// @ts-expect-error -- the bundle ships no type declarations
import { load } from "../node_modules/js-yaml/dist/js-yaml.mjs";

/**
 * Reads a cluster config (e.g. `cluster/deployment/<cluster>/config.resolved.yaml`) and returns
 * the service wide (global) rate limits declared in it, see `scan.example.yaml` for the shape.
 *
 * Per endpoint buckets (`rateLimits`) are deliberately ignored for now: only the global and the
 * global per-IP buckets are exercised, against a probe path that has no bucket of its own.
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

/** A service whose global rate limits are under test. */
export interface Target {
  /** where the `externalRateLimits` block sits in the config, e.g. `sv.scan` */
  name: string;
  /** the bucket for a single client IP, shared by all endpoints of the service */
  globalPerIpLimits?: ResolvedBucket;
  /** the bucket shared by all clients and all endpoints of the service */
  globalLimits?: ResolvedBucket;
  /** the URL under test, built from the domain and the probe path passed to main.ts */
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Collects every `externalRateLimits` block that declares a global bucket, wherever it sits in
 * the config.
 */
export function collectTargets(
  config: unknown,
  domain: string,
  probePath: string,
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
    if (!globalPerIpLimits && !globalLimits) {
      return;
    }
    targets.push({
      name: service,
      globalPerIpLimits,
      globalLimits,
      url: `https://${domain}${probePath}`,
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
  domain: string,
  probePath: string,
): Target[] {
  // `open` only exists in k6's init context and resolves relative paths against this file, while
  // the paths passed in are relative to the package root, so both are tried.
  const candidates = configPath.startsWith("/")
    ? [configPath]
    : [`../${configPath}`, configPath];
  for (const candidate of candidates) {
    try {
      return parseConfig(open(candidate), domain, probePath);
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
  domain: string,
  probePath: string,
): Target[] {
  return collectTargets(
    (load as (input: string) => unknown)(raw),
    domain,
    probePath,
  );
}

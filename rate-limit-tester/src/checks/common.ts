// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { RefinedResponse, ResponseType } from "k6/http";
import { check as k6check } from "k6";
import { Counter, Rate } from "k6/metrics";
import { Scenario } from "k6/options";
import {
  Endpoint,
  effectivePerIpBucket,
  effectiveSharedBucket,
} from "../config.ts";

/** One traffic phase of a check, e.g. staying below or going above a limit. */
export interface CheckScenario {
  /** identifies the phase within the check, e.g. 'below' / 'above' */
  phase: string;
  /** k6 scenario definition; `exec`, `startTime` and `tags` are filled in by main.ts */
  scenario: Scenario;
  /** seconds this scenario runs, used to schedule checks sequentially */
  durationSeconds: number;
  /** quiet seconds required *before* this scenario, so buckets can refill */
  recoverySeconds: number;
}
export interface CheckPlan {
  scenarios: CheckScenario[];
  /** thresholds keyed by metric selector, merged into `options.thresholds` */
  thresholds: Record<string, string[]>;
  /** the peak load the check applies, e.g. '2000 req/s (2x the binding 1000 req/s)' */
  peakLoad: string;
  /** phase that goes above the limit, i.e. the one that must be rejected */
  burstPhase: string;
  /** phase that stays below the limit, if any, i.e. the one that must not be rejected */
  belowPhase?: string;
}
export interface Check {
  /** stable id, used to name scenarios and tag metrics */
  id: string;
  /**
   * Returns why this check cannot run against `endpoint`, so main.ts can report skipped
   * endpoints instead of silently passing, or `undefined` if it can run.
   */
  inapplicable(endpoint: Endpoint): string | undefined;
  /** Derives the scenarios and thresholds for `endpoint` from its buckets. */
  plan(endpoint: Endpoint): CheckPlan;
  /** The request logic, executed by every VU iteration of this check's scenarios. */
  run(endpoint: Endpoint, phase: string): void;
}
/** The body the splice app returns when *it* rate limits a request (see HttpRateLimiter). */
const APP_RATE_LIMIT_BODY =
  "Too Many Requests: Server is busy, please try again later.";
/** The body Envoy's local rate limit filter returns. */
const ENVOY_RATE_LIMIT_BODY = "local_rate_limited";
/**
 * Tolerated share of responses that are neither 200 nor 429. Load always causes the odd
 * connection reset, but a large share means the load reached the app.
 */
export const UNEXPECTED_STATUS_TOLERANCE = 0.02;
/**
 * How far above the binding rate to drive traffic. Envoy keeps one bucket per proxy instance, so
 * raise it with `-e BURST_FACTOR=5` if a limit is reported as not enforced.
 */
const BURST_FACTOR = Number(__ENV.BURST_FACTOR ?? "2");
/** Hard ceiling on the generated load, so this cannot turn into a DoS by accident. */
const MAX_BURST_RPS = Number(__ENV.MAX_BURST_RPS ?? "2500");
/** Bounds on how long a burst is held: long enough to drain the bucket, never absurdly long. */
const MIN_BURST_SECONDS = 30;
const MAX_BURST_SECONDS = 120;
/** Cap on the quiet period before a burst; a partly refilled bucket only rejects sooner. */
const MAX_RECOVERY_SECONDS = 15;
/** The traffic shape derived from the buckets that apply to an endpoint. */
export interface BurstSizing {
  /** sustained rate the per-IP bucket allows */
  perIpRps: number;
  /** rate that has to be exceeded before anything is rejected */
  bindingRps: number;
  burstRps: number;
  burstSeconds: number;
  recoverySeconds: number;
}
/** Derives the traffic shape from an endpoint's buckets, or the reason why it cannot. */
export function burstSizing(endpoint: Endpoint): BurstSizing | string {
  const perIp = effectivePerIpBucket(endpoint);
  if (!perIp) {
    return "no per-IP bucket applies to this endpoint";
  }
  const shared = effectiveSharedBucket(endpoint);
  const { maxTokens, tokensPerFill, fillIntervalSeconds, sustainedRps } = perIp;
  // A single client drains the per-IP bucket and the shared one at once, so the rate to exceed
  // is the higher of the two.
  const bindingRps = Math.max(sustainedRps, shared?.sustainedRps ?? 0);
  const burstRps = Math.min(
    MAX_BURST_RPS,
    Math.ceil(bindingRps * BURST_FACTOR),
  );
  if (burstRps <= bindingRps) {
    return (
      `the binding rate (${bindingRps.toFixed(1)} req/s) is at or above the MAX_BURST_RPS ` +
      `ceiling of ${MAX_BURST_RPS} req/s; raise -e MAX_BURST_RPS`
    );
  }
  return {
    perIpRps: sustainedRps,
    bindingRps,
    burstRps,
    // hold long enough to drain the bucket, which refills while we drain it
    burstSeconds: Math.min(
      MAX_BURST_SECONDS,
      Math.max(
        MIN_BURST_SECONDS,
        Math.ceil(maxTokens / (burstRps - bindingRps)),
      ),
    ),
    recoverySeconds: Math.min(
      MAX_RECOVERY_SECONDS,
      Math.ceil((maxTokens / tokensPerFill) * fillIntervalSeconds),
    ),
  };
}
/** A flat scenario at `rps` for `seconds`; arrival rate, so slow responses do not lower it. */
export function flatScenario(
  phase: string,
  rps: number,
  seconds: number,
  recoverySeconds = 0,
): CheckScenario {
  return {
    phase,
    durationSeconds: seconds,
    recoverySeconds,
    scenario: {
      executor: "constant-arrival-rate",
      rate: rps,
      timeUnit: "1s",
      duration: `${seconds}s`,
      preAllocatedVUs: Math.max(10, Math.ceil(rps / 4)),
      maxVUs: Math.max(20, rps),
    },
  };
}
/** Why a check cannot drive `endpoint`, for the checks that need an HTTP route. */
export function inapplicableHttpLimited(
  endpoint: Endpoint,
): string | undefined {
  if (endpoint.grpc) {
    return "gRPC paths cannot be driven with plain HTTP requests";
  }
  return inapplicableLimited(endpoint);
}
function inapplicableLimited(endpoint: Endpoint): string | undefined {
  if (endpoint.type !== "limited") {
    return `endpoint type is '${endpoint.type}', not 'limited'`;
  }
  const sized = burstSizing(endpoint);
  return typeof sized === "string" ? sized : undefined;
}
/**
 * Metrics shared by all checks, so `handleSummary` can build a verdict without knowing which
 * check produced them. The `check` and `phase` tags keep the runs apart.
 */
const throttled = new Rate("rate_limit_throttled");
/** 429s that carry the splice app's rate limit error, i.e. enforced by the app. */
const throttledByApp = new Counter("rate_limit_throttled_by_app");
/** 429s that do not come from the app, i.e. enforced by the Envoy sidecar. */
const throttledByInfra = new Counter("rate_limit_throttled_by_infra");
/** Responses that are neither 200 nor 429; `passes` is their absolute count. */
const unexpectedStatusRate = new Rate("rate_limit_unexpected_status_rate");
/**
 * Builds a k6 submetric selector. Thresholds and `handleSummary` must spell a selector exactly
 * the same way, so both go through this helper.
 */
export function selector(metric: string, tags: Record<string, string>): string {
  const spelled = Object.entries(tags)
    .map(([key, value]) => `${key}:${value}`)
    .join(",");
  return `${metric}{${spelled}}`;
}
/** The thresholds every rate limit check asserts, scoped to one check and endpoint. */
export function rateLimitThresholds(
  scope: { endpoint: string; check: string },
  burstPhase: string,
  belowPhase?: string,
): Record<string, string[]> {
  const thresholds: Record<string, string[]> = {
    // The burst must be rejected by the sidecar, not by the app: shedding the load at the edge
    // is the whole point.
    [selector("rate_limit_throttled_by_infra", scope)]: ["count>0"],
    [selector("rate_limit_throttled_by_app", scope)]: ["count==0"],
    // NB: also makes k6 compute the submetric, which the summary reports.
    [selector("rate_limit_throttled", { ...scope, phase: burstPhase })]: [
      "rate>0",
    ],
    // Responses that are neither 200 nor 429 mean the load reached the app, but a small share of
    // them is just noise under load.
    [selector("rate_limit_unexpected_status_rate", scope)]: [
      `rate<${UNEXPECTED_STATUS_TOLERANCE}`,
    ],
  };
  if (belowPhase) {
    // Traffic that stays below the limit must not be rejected.
    thresholds[
      selector("rate_limit_throttled", { ...scope, phase: belowPhase })
    ] = ["rate==0"];
  }
  return thresholds;
}
function body(res: RefinedResponse<ResponseType | undefined>): string {
  return String(res.body ?? "");
}
/** Records one response against the shared metrics and checks, and reports whether it was 429. */
export function recordResponse(
  res: RefinedResponse<ResponseType | undefined>,
  tags: Record<string, string>,
): boolean {
  const rejected = res.status === 429;
  throttled.add(rejected, tags);
  unexpectedStatusRate.add(res.status !== 200 && !rejected, tags);
  if (rejected) {
    (body(res).includes(APP_RATE_LIMIT_BODY)
      ? throttledByApp
      : throttledByInfra
    ).add(1, tags);
  }
  k6check(
    res,
    {
      "status is 200 or 429": (r) => r.status === 200 || r.status === 429,
      "429 is not produced by the splice app": (r) =>
        r.status !== 429 || !body(r).includes(APP_RATE_LIMIT_BODY),
      "429 looks like an envoy local rate limit rejection": (r) =>
        r.status !== 429 ||
        body(r).includes(ENVOY_RATE_LIMIT_BODY) ||
        body(r).trim() === "",
      "rate limit headers are stripped at the ingress": (r) =>
        r.headers["X-Local-Rate-Limit"] === undefined &&
        r.headers["X-Ratelimit-Limit"] === undefined &&
        r.headers["X-Envoy-Ratelimited"] === undefined,
    },
    tags,
  );
  return rejected;
}
export function requestParams(
  tags: Record<string, string>,
  headers?: Record<string, string>,
): { tags: Record<string, string>; headers?: Record<string, string> } {
  // NB: no X-Forwarded-For by default. Istio trusts two proxy hops here, so a client supplied
  // entry could hand every VU its own bucket and turn the per-ip check into a no-op.
  return headers ? { tags, headers } : { tags };
}
/** Metric/scenario safe version of a name. */
export function slug(value: string): string {
  return value.replace(/[^a-zA-Z0-9]+/g, "_").replace(/^_+|_+$/g, "");
}

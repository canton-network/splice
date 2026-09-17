// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import http, { RefinedResponse, ResponseType } from "k6/http";
import { check as k6check } from "k6";
import exec from "k6/execution";
import { Counter, Rate } from "k6/metrics";
import { Scenario } from "k6/options";
import {
  RATE_LIMITED_GRPC_STATUS,
  Target,
  perIpBucket,
  sharedBucket,
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
   * Returns why this check cannot run against `target`, so main.ts can report it as skipped rather
   * than silently pass, or `undefined` if it can run.
   */
  inapplicable(target: Target): string | undefined;
  /** Derives the scenarios and thresholds for `target` from its global buckets. */
  plan(target: Target): CheckPlan;
  /** The request logic, executed by every VU iteration of this check's scenarios. */
  run(target: Target, phase: string): void;
}
/** The body the splice app returns when *it* rate limits a request (see HttpRateLimiter). */
const APP_RATE_LIMIT_BODY =
  "Too Many Requests: Server is busy, please try again later.";
/** The body Envoy's local rate limit filter returns. */
const ENVOY_RATE_LIMIT_BODY = "local_rate_limited";
/**
 * Tolerated share of responses that are neither 200 nor 429: load always causes the odd connection
 * reset, but a large share means it reached the app.
 */
export const UNEXPECTED_STATUS_TOLERANCE = 0.02;
/** How far above the binding rate to drive traffic. */
const BURST_FACTOR = Number(__ENV.BURST_FACTOR ?? "2");
/** Hard ceiling on the generated load, so this cannot turn into a DoS by accident. */
const MAX_BURST_RPS = Number(__ENV.MAX_BURST_RPS ?? "2500");
/**
 * Bounds on how long a burst is held. The per-IP floor is higher because such a bucket is really N
 * buckets (see `PER_IP_BUFFER_RPS`), i.e. N times as deep, and drains N times slower than
 * `drainSeconds` predicts. `-e BURST_SECONDS` pins the window instead of deriving it.
 */
const MIN_BURST_SECONDS = 30;
const MIN_PER_IP_BURST_SECONDS = 60;
const MAX_BURST_SECONDS = 120;
const BURST_SECONDS =
  __ENV.BURST_SECONDS === undefined ? undefined : Number(__ENV.BURST_SECONDS);
/** Cap on the quiet period before a burst; a partly refilled bucket only rejects sooner. */
const MAX_RECOVERY_SECONDS = 15;
/**
 * Flat buffer on the burst of a target bound by its per-IP bucket. Envoy keys that bucket on the
 * address the sidecar sees, i.e. the ingress gateway pod rather than the client, so N gateway
 * replicas allow N times the configured rate. Measured on the sequencer (166.7 req/s configured),
 * 344 and 510 req/s were absorbed entirely while 1334 req/s was rejected within 9s.
 */
const PER_IP_BUFFER_RPS = Number(__ENV.PER_IP_BUFFER_RPS ?? "1000");
/** The traffic shape derived from the global buckets that apply to a target. */
export interface BurstSizing {
  /** sustained rate the per-IP bucket allows */
  perIpRps: number;
  /** rate that has to be exceeded before anything is rejected */
  bindingRps: number;
  burstRps: number;
  burstSeconds: number;
  recoverySeconds: number;
}
/** Derives the traffic shape from a target's global buckets, or the reason why it cannot. */
export function burstSizing(target: Target): BurstSizing | string {
  const perIp = perIpBucket(target);
  if (!perIp) {
    return "no global per-IP bucket is configured for this service";
  }
  const { maxTokens, tokensPerFill, fillIntervalSeconds, sustainedRps } = perIp;
  // The rate to exceed is the higher of the two buckets a client drains at once. Over gRPC the
  // shared one is ignored: envoy enforces the per-IP limit in a filter of its own, and no single
  // client can drive the sequencer's ~3333 req/s global bucket.
  const shared = target.protocol === "grpc" ? undefined : sharedBucket(target);
  const bindingRps = Math.max(sustainedRps, shared?.sustainedRps ?? 0);
  // Only a per-IP bucket is split over the gateway replicas, so only it gets the buffer and window.
  const perIpBound = bindingRps === sustainedRps;
  const burstRps = Math.min(
    MAX_BURST_RPS,
    Math.ceil(bindingRps * BURST_FACTOR) + (perIpBound ? PER_IP_BUFFER_RPS : 0),
  );
  if (burstRps <= bindingRps) {
    return (
      `the binding rate (${bindingRps.toFixed(1)} req/s) is at or above the MAX_BURST_RPS ` +
      `ceiling of ${MAX_BURST_RPS} req/s; raise -e MAX_BURST_RPS`
    );
  }
  // The bucket refills while it is drained, so it empties only after maxTokens/(burst - binding)
  // seconds, and the burst is held for twice that: one merely long enough to empty it ends exactly
  // when the first rejection would happen, making the limit look unenforced.
  const drainSeconds = maxTokens / (burstRps - bindingRps);
  // A pinned window is taken at face value; the derived one has the 2x drain to fit under the cap.
  const maxWindow = BURST_SECONDS ?? MAX_BURST_SECONDS;
  if (drainSeconds * 2 > maxWindow) {
    return (
      `draining the ${maxTokens} token bucket at ${burstRps} req/s takes ` +
      `${drainSeconds.toFixed(0)}s, too long to then hold the burst past it within the ` +
      `${maxWindow}s window; raise -e BURST_FACTOR, -e PER_IP_BUFFER_RPS or -e BURST_SECONDS`
    );
  }
  return {
    perIpRps: sustainedRps,
    bindingRps,
    burstRps,
    burstSeconds:
      BURST_SECONDS ??
      Math.min(
        MAX_BURST_SECONDS,
        Math.max(
          perIpBound ? MIN_PER_IP_BURST_SECONDS : MIN_BURST_SECONDS,
          Math.ceil(drainSeconds * 2),
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
/** Why a check cannot drive `target`. */
export function inapplicableTarget(target: Target): string | undefined {
  const sized = burstSizing(target);
  return typeof sized === "string" ? sized : undefined;
}
/**
 * Metrics shared by all checks, so `handleSummary` can build a verdict without knowing which check
 * produced them. The `check` and `phase` tags keep the runs apart.
 */
const throttled = new Rate("rate_limit_throttled");
/** 429s that carry the splice app's rate limit error, i.e. enforced by the app. */
const throttledByApp = new Counter("rate_limit_throttled_by_app");
/** 429s that do not come from the app, i.e. enforced by the Envoy sidecar. */
const throttledByInfra = new Counter("rate_limit_throttled_by_infra");
/** Responses that are neither 200 nor 429; `passes` is their absolute count. */
const unexpectedStatusRate = new Rate("rate_limit_unexpected_status_rate");
/**
 * Builds a k6 submetric selector. Thresholds and `handleSummary` must spell one identically, so
 * both go through this helper.
 */
export function selector(metric: string, tags: Record<string, string>): string {
  const spelled = Object.entries(tags)
    .map(([key, value]) => `${key}:${value}`)
    .join(",");
  return `${metric}{${spelled}}`;
}
/** The thresholds every rate limit check asserts, scoped to one check and target. */
export function rateLimitThresholds(
  scope: { target: string; check: string },
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
/**
 * The gRPC status of a response. A rejected call is a trailers-only response, i.e. the status is in
 * the headers, which is the only place k6 exposes.
 */
function grpcStatus(
  res: RefinedResponse<ResponseType | undefined>,
): number | undefined {
  const status = res.headers["Grpc-Status"] ?? res.headers["grpc-status"];
  return status === undefined ? undefined : Number(status);
}
/** What one probe response tells the caller. */
export interface ProbeResult {
  /** the request was rate limited */
  rejected: boolean;
  /** the response is neither a success nor a rejection, e.g. a 503 or a connection reset */
  unexpected: boolean;
  /**
   * The response came from an upstream rather than from a proxy. The gateway answers a host it has
   * no route for itself, and for gRPC that reply is an ordinary 200 with a gRPC status; rate limits
   * live on the app's sidecar, so load that never reaches an upstream is never rate limited.
   */
  reachedApp: boolean;
  /** human readable status, so a run that is never rejected can still be diagnosed */
  description: string;
}
/**
 * Sends the probe request of `target` and records it against the shared metrics and checks. Envoy
 * rejects an HTTP request with `429` and a gRPC call with HTTP `200` plus `RESOURCE_EXHAUSTED`; any
 * other gRPC status means the call reached the app. gRPC is spoken over plain HTTP/2 rather than
 * `k6/net/grpc`, as the sidecar rate limits it before the app, so no proto descriptor is needed.
 */
export function probe(
  target: Target,
  tags: Record<string, string>,
  headers?: Record<string, string>,
): ProbeResult {
  // NB: no X-Forwarded-For by default. Istio trusts two proxy hops here, so a client supplied
  // entry could hand every VU its own bucket and turn the per-ip check into a no-op.
  const res =
    target.protocol === "grpc"
      ? // a length prefixed frame of an empty message: not compressed, zero bytes long
        http.post(target.url, new Uint8Array([0, 0, 0, 0, 0]).buffer, {
          tags,
          headers: {
            "Content-Type": "application/grpc",
            TE: "trailers",
            ...(headers ?? {}),
          },
        })
      : http.get(target.url, headers ? { tags, headers } : { tags });

  const grpc = target.protocol === "grpc";
  const status = grpcStatus(res);
  const rejected = grpc
    ? res.status === 200 && status === RATE_LIMITED_GRPC_STATUS
    : res.status === 429;
  // For gRPC, a response without a gRPC status at all (e.g. HTTP/2 was not negotiated) never
  // reached the gRPC server, so it is not a success either.
  const unexpected =
    !rejected && (res.status !== 200 || (grpc && status === undefined));
  // The app rejects with its own body (HTTP) or without envoy's message (gRPC).
  const byApp = grpc
    ? !String(res.headers["Grpc-Message"] ?? "").includes(ENVOY_RATE_LIMIT_BODY)
    : body(res).includes(APP_RATE_LIMIT_BODY);

  throttled.add(rejected, tags);
  unexpectedStatusRate.add(unexpected, tags);
  if (rejected) {
    (byApp ? throttledByApp : throttledByInfra).add(1, tags);
  }
  k6check(
    res,
    {
      "response is a success or a rate limit rejection": () => !unexpected,
      "the rejection is not produced by the splice app": () =>
        !rejected || !byApp,
      "the rejection looks like an envoy local rate limit rejection": (r) =>
        !rejected ||
        grpc ||
        body(r).includes(ENVOY_RATE_LIMIT_BODY) ||
        body(r).trim() === "",
      "rate limit headers are stripped at the ingress": (r) =>
        r.headers["X-Local-Rate-Limit"] === undefined &&
        r.headers["X-Ratelimit-Limit"] === undefined &&
        r.headers["X-Envoy-Ratelimited"] === undefined,
    },
    tags,
  );
  // envoy only sets this once a response has come back from an upstream, so a locally generated
  // reply (e.g. the gateway having no route for this host) does not carry it
  const upstreamTime = res.headers["X-Envoy-Upstream-Service-Time"];
  return {
    rejected,
    unexpected,
    reachedApp: upstreamTime !== undefined,
    description: grpc
      ? `HTTP ${res.status}, gRPC status ${status ?? "absent (in the trailers?)"}` +
        `${res.headers["Grpc-Message"] ? `, '${res.headers["Grpc-Message"]}'` : ""}` +
        `, upstream ${upstreamTime === undefined ? "NOT reached" : `reached in ${upstreamTime}ms`}`
      : `HTTP ${res.status}`,
  };
}
/** Metric/scenario safe version of a name. */
export function slug(value: string): string {
  return value.replace(/[^a-zA-Z0-9]+/g, "_").replace(/^_+|_+$/g, "");
}

/**
 * Rejections the test waits for before it stops sending: the limit is proven at that point, and the
 * scenarios keep scheduling iterations that no longer put load on the cluster.
 */
const REJECTIONS_TO_PROVE = Number(__ENV.REJECTIONS_TO_PROVE ?? "50");
const rejectionsSeen: Record<string, number> = {};

/** The share of `REJECTIONS_TO_PROVE` this VU is responsible for, given the VUs running now. */
function perVuBudget(): number {
  return Math.max(
    1,
    Math.ceil(REJECTIONS_TO_PROVE / Math.max(1, exec.instance.vusActive)),
  );
}

/** True once this VU has proven the limit for `checkId`/`phase`, i.e. it can stop sending. */
export function limitProven(checkId: string, phase: string): boolean {
  return (rejectionsSeen[`${checkId}:${phase}`] ?? 0) >= perVuBudget();
}

/** Records a rejection towards the proof of `checkId`/`phase` for this VU. */
export function recordRejection(checkId: string, phase: string): void {
  const key = `${checkId}:${phase}`;
  rejectionsSeen[key] = (rejectionsSeen[key] ?? 0) + 1;
}

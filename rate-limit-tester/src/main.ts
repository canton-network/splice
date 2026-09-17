// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import exec from "k6/execution";
import { Options, Scenario } from "k6/options";
import { Target, loadConfig } from "./config.ts";
import { checks } from "./checks/index.ts";
import {
  UNEXPECTED_STATUS_TOLERANCE,
  probe,
  selector,
  slug,
} from "./checks/common.ts";

/**
 * Entry point of the rate limit tester: takes a domain and a cluster config, and runs every check
 * in `checks/` against the global rate limits of every service declared in it. Per endpoint buckets
 * are out of scope, so the load goes to a probe path that has no bucket of its own (`PROBES` in
 * `config.ts`, override with `-e PROBE_PATH=`). One host per run, so scan and the sequencer are run
 * separately, and scenarios run one after the other, as a service shares its buckets between them.
 *
 *   k6 run src/main.ts -e DOMAIN=scan.sv-2.example.com -e CONFIG=./scan.example.yaml
 *   k6 run src/main.ts -e DOMAIN=sequencer-0.sv-2.example.com -e CONFIG=./sequencer.example.yaml
 */
const DOMAIN = __ENV.DOMAIN ?? "";
const CONFIG = __ENV.CONFIG ?? "";
const PROBE_PATH = __ENV.PROBE_PATH || undefined;
if (DOMAIN === "" || CONFIG === "") {
  throw new Error(
    "DOMAIN and CONFIG are required, e.g. " +
      "k6 run src/main.ts -e DOMAIN=scan.sv-2.example.com -e CONFIG=./scan.example.yaml",
  );
}

interface ScenarioTarget {
  target: Target;
  checkId: string;
  phase: string;
}

/** What the summary needs to report the verdict of one check on one target. */
interface Report {
  target: Target;
  checkId: string;
  peakLoad: string;
  burstPhase: string;
  belowPhase?: string;
}

/**
 * k6 only accepts `exec` functions exported by this module, so every scenario runs `run` and looks
 * its target up here by scenario name. Each VU rebuilds the plan in its init context.
 */
const targets: Record<string, ScenarioTarget> = {};
const scenarios: Record<string, Scenario> = {};
const thresholds: Record<string, string[]> = {};
const reports: Report[] = [];
const skipped: string[] = [];
let totalSeconds = 0;
for (const target of loadConfig(CONFIG, DOMAIN, PROBE_PATH)) {
  for (const check of checks) {
    const reason = check.inapplicable(target);
    if (reason) {
      skipped.push(`${check.id} on ${target.name}: ${reason}`);
      continue;
    }
    const plan = check.plan(target);
    Object.assign(thresholds, plan.thresholds);
    reports.push({
      target,
      checkId: check.id,
      peakLoad: plan.peakLoad,
      burstPhase: plan.burstPhase,
      belowPhase: plan.belowPhase,
    });
    for (const {
      phase,
      scenario,
      durationSeconds,
      recoverySeconds,
    } of plan.scenarios) {
      const name = `${slug(check.id)}__${slug(target.name)}__${slug(phase)}`;
      totalSeconds += recoverySeconds;
      scenarios[name] = {
        ...scenario,
        exec: "run",
        startTime: `${totalSeconds}s`,
        tags: { check: slug(check.id), target: slug(target.name), phase },
      };
      targets[name] = { target, checkId: check.id, phase };
      // Small gap, so the tail of one scenario cannot be charged to the next one.
      totalSeconds += durationSeconds + 1;
    }
  }
}

if (Object.keys(scenarios).length === 0) {
  throw new Error(
    `no runnable check for the global rate limits declared in ${CONFIG}` +
      (skipped.length > 0 ? `; skipped ${skipped.join("; ")}` : ""),
  );
}

export const options: Options = { scenarios, thresholds };

export function setup(): void {
  // Fail fast if the probe path is not served here: an unrouted host answers with an empty 404,
  // which would otherwise look like a clean "never rate limited" run.
  const seen = new Set<string>();
  for (const { target } of reports) {
    if (seen.has(target.url)) {
      continue;
    }
    seen.add(target.url);
    // A rejection here is fine, it just means the bucket is already drained.
    const result = probe(target, { phase: "preflight" });
    // Always reported: a run that is never rate limited can only be diagnosed if what the endpoint
    // actually answers is known.
    console.log(`preflight ${target.url} -> ${result.description}`);
    if (result.unexpected || (!result.rejected && !result.reachedApp)) {
      throw new Error(
        `preflight ${target.protocol} request to ${target.url} did not reach the app ` +
          `(${result.description}). The rate limits are enforced by the app's own sidecar, so ` +
          "load that stops at the ingress gateway can never be rate limited. Is the host right " +
          "and routed to the app" +
          (target.protocol === "grpc"
            ? ", and is HTTP/2 negotiated on it?"
            : "?"),
      );
    }
  }
}

export function run(): void {
  const scenarioTarget = targets[exec.scenario.name];
  const check = checks.find((c) => c.id === scenarioTarget?.checkId);
  if (!scenarioTarget || !check) {
    throw new Error(`no check registered for scenario '${exec.scenario.name}'`);
  }
  check.run(scenarioTarget.target, scenarioTarget.phase);
}

/** Minimal view of what k6 hands to `handleSummary`. */
interface SummaryData {
  metrics: Record<string, { values: Record<string, number> }>;
}

function metric(
  data: SummaryData,
  name: string,
  tags: Record<string, string>,
  key: string,
): number {
  return data.metrics[selector(name, tags)]?.values?.[key] ?? 0;
}

/**
 * k6 reports a failure as the list of thresholds that were crossed, which does not say whether the
 * limit was missing, enforced by the wrong component or simply never reached. This reports the
 * verdict per check and target instead.
 */
export function handleSummary(data: SummaryData): Record<string, string> {
  const lines: string[] = ["", `rate limits of ${DOMAIN}:`, ""];
  let failed = false;
  for (const report of reports) {
    const scope = {
      target: slug(report.target.name),
      check: slug(report.checkId),
    };
    const byInfra = metric(
      data,
      "rate_limit_throttled_by_infra",
      scope,
      "count",
    );
    const byApp = metric(data, "rate_limit_throttled_by_app", scope, "count");
    const burstRate = metric(
      data,
      "rate_limit_throttled",
      { ...scope, phase: report.burstPhase },
      "rate",
    );
    const unexpected = metric(
      data,
      "rate_limit_unexpected_status_rate",
      scope,
      "passes",
    );
    const verdicts: string[] = [];
    if (byInfra === 0 && byApp === 0) {
      failed = true;
      verdicts.push(
        `NOT ENFORCED at ${report.peakLoad}: not a single request was rejected. Either the limit ` +
          "is looser than configured (envoy keeps a bucket per proxy instance, keyed on the " +
          "ingress gateway pod rather than on this client, so N gateway replicas allow N times " +
          "the rate: retry with a higher -e PER_IP_BUFFER_RPS, and a -e BURST_SECONDS long enough " +
          "to drain a bucket N times as deep), or no limit is installed at all. Confirm with " +
          "envoy_http_local_rate_limit_enabled.",
      );
    } else if (byApp > 0) {
      failed = true;
      verdicts.push(
        `ENFORCED BY THE APP: ${byApp} rejection(s) came from the splice app itself, not from ` +
          "the infrastructure, so the app carries load the sidecar should have shed.",
      );
    } else {
      verdicts.push(
        `ENFORCED by the infrastructure: ${byInfra} request(s) rejected, ` +
          `${(burstRate * 100).toFixed(1)}% of the load.`,
      );
    }
    if (report.belowPhase) {
      const belowRate = metric(
        data,
        "rate_limit_throttled",
        { ...scope, phase: report.belowPhase },
        "rate",
      );
      if (belowRate > 0) {
        failed = true;
        verdicts.push(
          `OVER-ENFORCED: ${(belowRate * 100).toFixed(1)}% of the traffic that stayed below the ` +
            "limit was rejected, so legitimate clients are throttled.",
        );
      }
    }
    if (unexpected > 0) {
      const share = metric(
        data,
        "rate_limit_unexpected_status_rate",
        scope,
        "rate",
      );
      const tolerated = share < UNEXPECTED_STATUS_TOLERANCE;
      failed = failed || !tolerated;
      verdicts.push(
        `${unexpected} response(s), ${(share * 100).toFixed(2)}%, were neither a success nor a rejection ` +
          "(e.g. 503 upstream connect error), i.e. that load reached the app instead of being " +
          `shed at the edge${tolerated ? " (within tolerance)" : ""}.`,
      );
    }
    lines.push(
      `  [${report.checkId}] ${report.target.name} (${report.target.url})`,
    );
    verdicts.forEach((v) => lines.push(`    - ${v}`));
    lines.push("");
  }
  skipped.forEach((s) => lines.push(`  skipped ${s}`));
  lines.push("", failed ? "  => FAILED" : "  => PASSED", "");
  return { stdout: lines.join("\n") };
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Target } from "../config.ts";
import {
  Check,
  CheckPlan,
  burstSizing,
  flatScenario,
  inapplicableTarget,
  limitProven,
  rateLimitThresholds,
  recordRejection,
  probe,
  slug,
} from "./common.ts";
/**
 * Verifies that a service's global per-IP rate limit is enforced by the Envoy sidecar and not by
 * the splice app itself.
 */
const CHECK_ID = "per-ip-rate-limit";

export const perIpRateLimitCheck: Check = {
  id: CHECK_ID,
  inapplicable: inapplicableTarget,
  plan(target: Target): CheckPlan {
    const sized = burstSizing(target);
    if (typeof sized === "string") {
      throw new Error(
        `cannot plan per-ip-rate-limit for ${target.name}: ${sized}`,
      );
    }
    // Half the per-IP rate: the bucket refills faster than we drain it, so a correctly
    // configured limit may never reject, whatever the replica count.
    const belowRps = Math.max(1, Math.floor(sized.perIpRps / 2));
    // The gRPC services carry per endpoint buckets far tighter than their global per-IP one (the
    // sequencer caps SequencerConnectService at 100 tokens/min per IP, against 10000 globally),
    // and this tester models only the global ones. Traffic at half the global per-IP rate can
    // therefore legitimately be rejected, so the below phase would report a bogus OVER-ENFORCED.
    const withBelow = target.protocol !== "grpc";
    return {
      peakLoad: `${sized.burstRps} req/s (binding rate ${sized.bindingRps.toFixed(1)} req/s)`,
      burstPhase: "above",
      belowPhase: withBelow ? "below" : undefined,
      scenarios: [
        ...(withBelow ? [flatScenario("below", belowRps, 20)] : []),
        flatScenario(
          "above",
          sized.burstRps,
          sized.burstSeconds,
          sized.recoverySeconds,
        ),
      ],
      thresholds: rateLimitThresholds(
        { target: slug(target.name), check: slug(CHECK_ID) },
        "above",
        withBelow ? "below" : undefined,
      ),
    };
  },
  run(target: Target, phase: string): void {
    // The limit is proven for this VU and phase, so stop sending: the scenario keeps scheduling
    // iterations, but they no longer put load on the cluster.
    if (phase === "above" && limitProven(CHECK_ID, phase)) {
      return;
    }
    const tags = {
      target: slug(target.name),
      phase,
      check: slug(CHECK_ID),
    };
    if (probe(target, tags).rejected) {
      recordRejection(CHECK_ID, phase);
    }
  },
};

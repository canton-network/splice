// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import http from "k6/http";
import { Endpoint } from "../config.ts";
import {
  Check,
  CheckPlan,
  burstSizing,
  flatScenario,
  inapplicableHttpLimited,
  rateLimitThresholds,
  recordResponse,
  requestParams,
  slug,
} from "./common.ts";
/**
 * Verifies that an endpoint's rate limit is enforced by the Envoy sidecar and not by the splice
 * app itself.
 */

/** Rejections a VU waits for before it stops sending: the limit is proven at that point. */
const REJECTIONS_TO_PROVE = 5;
/** Per VU, and therefore reset for every VU k6 starts. */
let rejectionsSeen = 0;
export const perIpRateLimitCheck: Check = {
  id: "per-ip-rate-limit",
  inapplicable: inapplicableHttpLimited,
  plan(endpoint: Endpoint): CheckPlan {
    const sized = burstSizing(endpoint);
    if (typeof sized === "string") {
      throw new Error(
        `cannot plan per-ip-rate-limit for ${endpoint.name}: ${sized}`,
      );
    }
    // Half the per-IP rate: the bucket refills faster than we drain it, so a correctly
    // configured limit may never reject, whatever the replica count.
    const belowRps = Math.max(1, Math.floor(sized.perIpRps / 2));
    return {
      peakLoad: `${sized.burstRps} req/s (binding rate ${sized.bindingRps} req/s)`,
      burstPhase: "above",
      belowPhase: "below",
      scenarios: [
        flatScenario("below", belowRps, 20),
        flatScenario(
          "above",
          sized.burstRps,
          sized.burstSeconds,
          sized.recoverySeconds,
        ),
      ],
      thresholds: rateLimitThresholds(
        { endpoint: slug(endpoint.name), check: slug("per-ip-rate-limit") },
        "above",
        "below",
      ),
    };
  },
  run(endpoint: Endpoint, phase: string): void {
    // The limit is proven for this VU, so stop sending: the scenario keeps scheduling
    // iterations, but they no longer put load on the cluster.
    if (phase === "above" && rejectionsSeen >= REJECTIONS_TO_PROVE) {
      return;
    }
    const tags = {
      endpoint: slug(endpoint.name),
      phase,
      check: slug("per-ip-rate-limit"),
    };
    if (recordResponse(http.get(endpoint.url, requestParams(tags)), tags)) {
      rejectionsSeen += 1;
    }
  },
};

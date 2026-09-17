// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import http from "k6/http";
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
  recordResponse,
  requestParams,
  slug,
} from "./common.ts";

/**
 * Verifies that a client cannot get a fresh per-IP bucket by forging the headers that carry a
 * client address.
 */
const CHECK_ID = "client-ip-spoofing";

/**
 * A different, routable looking IPv4 address per request. 1.0.0.0-223.255.255.255 avoids the
 * multicast and reserved ranges, which a proxy might drop rather than simply distrust.
 */
function forgedIp(): string {
  const octet = (max: number): number => 1 + Math.floor(Math.random() * max);
  return `${octet(222)}.${octet(253)}.${octet(253)}.${octet(253)}`;
}

/** Every header a hop could be read from, all claiming the same forged address. */
function spoofedHeaders(): Record<string, string> {
  const ip = forgedIp();
  return {
    // a full chain, so that trusting the first, the last or the nth hop all yield a forged IP
    "X-Forwarded-For": `${ip}, ${forgedIp()}, ${forgedIp()}`,
    "X-Real-IP": ip,
    "X-Envoy-External-Address": ip,
    "X-Client-IP": ip,
    Forwarded: `for=${ip}`,
  };
}

export const clientIpSpoofingCheck: Check = {
  id: CHECK_ID,
  inapplicable: inapplicableTarget,
  plan(target: Target): CheckPlan {
    const sized = burstSizing(target);
    if (typeof sized === "string") {
      throw new Error(
        `cannot plan client-ip-spoofing for ${target.name}: ${sized}`,
      );
    }
    return {
      peakLoad: `${sized.burstRps} req/s, each request claiming a different client IP`,
      burstPhase: "spoofed",
      scenarios: [
        flatScenario(
          "spoofed",
          sized.burstRps,
          sized.burstSeconds,
          sized.recoverySeconds,
        ),
      ],
      thresholds: rateLimitThresholds(
        { target: slug(target.name), check: slug(CHECK_ID) },
        "spoofed",
      ),
    };
  },

  run(target: Target, phase: string): void {
    // The limit is proven for this VU and phase, so stop sending: the scenario keeps scheduling
    // iterations, but they no longer put load on the cluster.
    if (limitProven(CHECK_ID, phase)) {
      return;
    }
    const tags = {
      target: slug(target.name),
      phase,
      check: slug(CHECK_ID),
    };
    if (
      recordResponse(
        http.get(target.url, requestParams(tags, spoofedHeaders())),
        tags,
      )
    ) {
      recordRejection(CHECK_ID, phase);
    }
  },
};

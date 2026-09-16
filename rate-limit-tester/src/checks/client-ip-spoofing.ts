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
 * Verifies that a client cannot get a fresh per-IP bucket by forging the headers that carry a
 * client address.
 */
const REJECTIONS_TO_PROVE = 5;
let rejectionsSeen = 0;

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
  id: "client-ip-spoofing",
  inapplicable: inapplicableHttpLimited,
  plan(endpoint: Endpoint): CheckPlan {
    const sized = burstSizing(endpoint);
    if (typeof sized === "string") {
      throw new Error(
        `cannot plan client-ip-spoofing for ${endpoint.name}: ${sized}`,
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
        { endpoint: slug(endpoint.name), check: slug("client-ip-spoofing") },
        "spoofed",
      ),
    };
  },

  run(endpoint: Endpoint, phase: string): void {
    if (rejectionsSeen >= REJECTIONS_TO_PROVE) {
      return;
    }
    const tags = {
      endpoint: slug(endpoint.name),
      phase,
      check: slug("client-ip-spoofing"),
    };
    if (
      recordResponse(
        http.get(endpoint.url, requestParams(tags, spoofedHeaders())),
        tags,
      )
    ) {
      rejectionsSeen += 1;
    }
  },
};

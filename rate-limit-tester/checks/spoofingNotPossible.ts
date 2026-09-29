// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { describeUnexpected, exhaust } from '../probe.ts';
import type { Check } from './index.ts';

const FAKE_IPS = ['203.0.113.7', '203.0.113.8', '203.0.113.9'];

const SPOOFED_HEADERS: Record<string, string> = {
  'x-forwarded-for': FAKE_IPS.join(', '),
  'x-real-ip': FAKE_IPS[0],
  forwarded: `for=${FAKE_IPS[0]}`,
  'x-envoy-external-address': FAKE_IPS[0],
};

const REQUESTS_PER_ATTEMPT = 20;

export const spoofingNotPossible: Check = {
  name: 'spoofing-not-possible',
  async run(probe, component) {
    const burst = await exhaust(probe, component.maxRequestsPerIp);
    if (!burst.exhausted) {
      return `limit not enforced, so spoofing cannot be assessed${describeUnexpected(burst)}`;
    }

    const bypassingHeaders: string[] = [];
    for (const [header, value] of Object.entries(SPOOFED_HEADERS)) {
      const spoofed = await probe.send(REQUESTS_PER_ATTEMPT, { [header]: value });
      const control = await probe.send(REQUESTS_PER_ATTEMPT);
      if (control.accepted > 0) {
        return 'inconclusive: the limit refilled during the check, rerun';
      }
      if (spoofed.accepted > 0) {
        bypassingHeaders.push(`${header} (${spoofed.accepted}/${REQUESTS_PER_ATTEMPT} accepted)`);
      }
    }
    return bypassingHeaders.length > 0
      ? `limit bypassed via ${bypassingHeaders.join(', ')}`
      : undefined;
  },
};

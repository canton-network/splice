// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { check, sleep } from 'k6';
import type { Options } from 'k6/options';

import { CHECKS } from './checks/index.ts';
import { loadConfig } from './config.ts';
import { probeFor } from './probe.ts';

const targets = loadConfig(__ENV.CONFIG).map(component => ({
  component,
  probe: probeFor(component),
}));

export const options: Options = {
  scenarios: {
    rateLimits: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '1h' },
  },
  thresholds: { checks: ['rate==1'] },
};

export default async function (): Promise<void> {
  for (const { component, probe } of targets) {
    for (const rateLimitCheck of CHECKS) {
      sleep(component.windowSeconds);
      const failure = await rateLimitCheck.run(probe, component);
      const label = `${component.name}: ${rateLimitCheck.name}`;
      if (failure) console.error(`${label}: ${failure}`);
      check(failure, { [label]: reason => reason === undefined });
    }
  }
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import assert from "node:assert/strict";
import test from "node:test";

import {
  collectTargets,
  parseConfig,
  parseDurationSeconds,
  perIpBucket,
  sharedBucket,
} from "./config.ts";

const PROBE_PATH = "/api/scan/version";

const exampleYaml = `
sv:
  scan:
    externalRateLimits:
      globalLimits:
        maxTokens: 60000
        tokensPerFill: 10000
        fillInterval: '10s'
      globalPerIpLimits:
        maxTokens: 6000
        tokensPerFill: 1000
        fillInterval: '10s'
      rateLimits:
        /api/scan/other:
          name: 'scan-other'
          type: 'limited'
          maxTokens: 10
          tokensPerFill: 10
          fillInterval: '60s'
  synchronizer:
    sequencer:
      externalRateLimits:
        globalPerIpLimits:
          maxTokens: 100
          tokensPerFill: 100
          fillInterval: '60s'
  validator:
    externalRateLimits:
      rateLimits:
        /api/validator/version:
          name: 'validator-version'
          type: 'limited'
`;

test("parseDurationSeconds understands the config duration units", () => {
  assert.equal(parseDurationSeconds("60s"), 60);
  assert.equal(parseDurationSeconds("10"), 10);
  assert.equal(parseDurationSeconds("2m"), 120);
  assert.equal(parseDurationSeconds("500ms"), 0.5);
  assert.throws(() => parseDurationSeconds("soon"));
});

test("only services declaring a global bucket are collected", () => {
  const targets = parseConfig(exampleYaml, "scan.sv-2.example.com", PROBE_PATH);
  assert.deepEqual(
    targets.map((t) => t.name),
    ["sv.scan", "sv.synchronizer.sequencer"],
  );
});

test("targets are resolved to the probe path under the domain", () => {
  const [scan] = parseConfig(exampleYaml, "scan.sv-2.example.com", PROBE_PATH);
  assert.equal(scan.url, "https://scan.sv-2.example.com/api/scan/version");
});

test("the global buckets are the ones under test, per endpoint ones are ignored", () => {
  const [scan, sequencer] = parseConfig(
    exampleYaml,
    "scan.sv-2.example.com",
    PROBE_PATH,
  );

  assert.equal(perIpBucket(scan)?.sustainedRps, 100);
  assert.equal(sharedBucket(scan)?.sustainedRps, 1000);

  assert.equal(perIpBucket(sequencer)?.sustainedRps, 100 / 60);
  assert.equal(sharedBucket(sequencer), undefined);
});

test("a global per-IP bucket alone is enough to build a target", () => {
  const targets = collectTargets(
    {
      sv: {
        scan: {
          externalRateLimits: {
            globalPerIpLimits: {
              maxTokens: 1000,
              tokensPerFill: 750,
              fillInterval: "60s",
            },
          },
        },
      },
    },
    "scan.sv-2.example.com",
    PROBE_PATH,
  );
  assert.equal(targets.length, 1);
  assert.equal(perIpBucket(targets[0])?.sustainedRps, 12.5);
});

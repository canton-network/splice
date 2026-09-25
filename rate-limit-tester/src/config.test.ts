// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import assert from "node:assert/strict";
import test from "node:test";

import {
  type ServiceUrls,
  collectTargets,
  normalizeBaseUrl,
  parseConfig,
  parseDurationSeconds,
  perIpBucket,
  sharedBucket,
} from "./config.ts";

const PROBE_PATH = "/api/scan/version";

const URLS: ServiceUrls = {
  scan: "https://scan.sv-2.example.com",
  sequencer: "https://sequencer-17.sv-2.example.com",
};

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
  const targets = parseConfig(exampleYaml, URLS);
  assert.deepEqual(
    targets.map((t) => t.name),
    ["sv.scan", "sv.synchronizer.sequencer"],
  );
});

test("a service without a URL is not part of the run", () => {
  const targets = parseConfig(exampleYaml, { scan: URLS.scan });
  assert.deepEqual(
    targets.map((t) => t.name),
    ["sv.scan"],
  );
});

test("a base URL may be given with or without a scheme or trailing slash", () => {
  assert.equal(
    normalizeBaseUrl("scan.example.com"),
    "https://scan.example.com",
  );
  assert.equal(
    normalizeBaseUrl("https://scan.example.com/"),
    "https://scan.example.com",
  );
  assert.equal(
    normalizeBaseUrl(" http://scan.example.com "),
    "http://scan.example.com",
  );
  const [scan] = parseConfig(exampleYaml, { scan: "scan.example.com/" });
  assert.equal(scan.url, "https://scan.example.com/api/scan/version");
});

test("each service is probed on its own host, path and protocol", () => {
  const [scan, sequencer] = parseConfig(exampleYaml, URLS);

  assert.equal(scan.protocol, "http");
  assert.equal(scan.url, "https://scan.sv-2.example.com/api/scan/version");

  // The sequencer's public API is gRPC, and GetTime has no per endpoint bucket of its own, so it
  // is charged against the global buckets only.
  assert.equal(sequencer.protocol, "grpc");
  assert.equal(
    sequencer.url,
    "https://sequencer-17.sv-2.example.com/com.digitalasset.canton.sequencer.api.v30.SequencerService/GetTime",
  );
});

test("PROBE_PATH overrides the probe path of every service", () => {
  const [scan] = parseConfig(
    exampleYaml,
    { scan: URLS.scan },
    "/api/scan/other",
  );
  assert.equal(scan.url, "https://scan.sv-2.example.com/api/scan/other");
});

test("a service this tester cannot probe is skipped rather than driven", () => {
  const targets = collectTargets(
    {
      sv: {
        mediator: {
          externalRateLimits: {
            globalPerIpLimits: {
              maxTokens: 10,
              tokensPerFill: 10,
              fillInterval: "60s",
            },
          },
        },
      },
    },
    { ...URLS, mediator: "https://mediator.sv-2.example.com" },
  );
  assert.deepEqual(targets, []);
});

test("the global buckets are the ones under test, per endpoint ones are ignored", () => {
  const [scan, sequencer] = parseConfig(exampleYaml, URLS);

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
    URLS,
    PROBE_PATH,
  );
  assert.equal(targets.length, 1);
  assert.equal(perIpBucket(targets[0])?.sustainedRps, 12.5);
});

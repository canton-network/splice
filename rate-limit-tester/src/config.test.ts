// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import assert from "node:assert/strict";
import test from "node:test";

import {
  collectTestedEndpoints,
  effectivePerIpBucket,
  effectiveSharedBucket,
  parseConfig,
  parseDurationSeconds,
} from "./config.ts";

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
        /api/scan/version:
          test: true
          name: 'scan-version'
          type: 'limited'
        /api/scan/other:
          name: 'scan-other'
          type: 'limited'
        /api/scan/off:
          test: false
          name: 'scan-off'
          type: 'limited'
  synchronizer:
    sequencer:
      externalRateLimits:
        rateLimits:
          /com.digitalasset.canton.sequencer.api.v30.SequencerConnectService/:
            test: true
            name: 'sequencer-connect'
            type: 'limited'
            maxTokens: 10000
            tokensPerFill: 10000
            fillInterval: '60s'
            perIpLimits:
              maxTokens: 100
              tokensPerFill: 100
              fillInterval: '60s'
`;

test("parseDurationSeconds understands the config duration units", () => {
  assert.equal(parseDurationSeconds("60s"), 60);
  assert.equal(parseDurationSeconds("10"), 10);
  assert.equal(parseDurationSeconds("2m"), 120);
  assert.equal(parseDurationSeconds("500ms"), 0.5);
  assert.throws(() => parseDurationSeconds("soon"));
});

test("only endpoints flagged with test: true are collected", () => {
  const endpoints = parseConfig(exampleYaml, "scan.sv-2.example.com");
  assert.deepEqual(
    endpoints.map((e) => e.name),
    ["scan-version", "sequencer-connect"],
  );
});

test("endpoints are resolved to a URL under the domain, and gRPC paths flagged", () => {
  const [scan, sequencer] = parseConfig(exampleYaml, "scan.sv-2.example.com");
  assert.equal(scan.url, "https://scan.sv-2.example.com/api/scan/version");
  assert.equal(scan.grpc, false);
  assert.equal(sequencer.grpc, true);
});

test("the stricter per-IP bucket and the lowest shared bucket are selected", () => {
  const [scan, sequencer] = parseConfig(exampleYaml, "scan.sv-2.example.com");

  // scan has no per-endpoint limits, so the service wide buckets apply
  assert.equal(effectivePerIpBucket(scan)?.sustainedRps, 100);
  assert.equal(effectiveSharedBucket(scan)?.sustainedRps, 1000);

  // the sequencer endpoint's own perIpLimits is the only per-IP bucket
  assert.equal(effectivePerIpBucket(sequencer)?.sustainedRps, 100 / 60);
});

test("an endpoint level per-IP bucket wins over a looser global one", () => {
  const [endpoint] = collectTestedEndpoints(
    {
      sv: {
        scan: {
          externalRateLimits: {
            globalPerIpLimits: {
              maxTokens: 1000,
              tokensPerFill: 1000,
              fillInterval: "60s",
            },
            rateLimits: {
              "/api/scan/version": {
                test: true,
                name: "scan-version",
                type: "limited",
                perIpLimits: {
                  maxTokens: 750,
                  tokensPerFill: 750,
                  fillInterval: "60s",
                },
              },
            },
          },
        },
      },
    },
    "scan.sv-2.example.com",
  );
  assert.equal(effectivePerIpBucket(endpoint)?.sustainedRps, 12.5);
});

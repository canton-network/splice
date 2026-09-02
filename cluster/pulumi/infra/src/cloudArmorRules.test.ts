// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { expect, test, describe } from '@jest/globals';

import {
  allowedPathsCondition,
  hostCondition,
  ipWhitelistRuleChunks,
  matchExpression,
  MAX_IPS_PER_RULE,
  MAX_IP_WHITELIST_RULES,
  MAX_SUBEXPRESSION_LENGTH,
} from './cloudArmorRules';

const dnsNames = [
  'scratchd.network.canton.global',
  'scratchd.global.canton.network.digitalasset.com',
];

const scanRateLimits = {
  rateLimits: {
    '/api/scan/v0/dso': { name: 'dso', type: 'limited' },
    '/registry/metadata/v1/info': { name: 'info', type: 'limited' },
    // not externally reachable, so extractPathPrefixes drops it
    '/api/sv/v0/dso': { name: 'sv-dso', type: 'limited' },
  },
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
} as any;

// mirrors how Cloud Armor evaluates `matches`: an unanchored RE2 search on the
// normalized value, with our expressions anchoring explicitly via ^
function matchesFromExpr(expr: string): (value: string) => boolean {
  const asMatches =
    /^request\.(?:headers\['host'\]|path)\.lower\(\)(?:\.urlDecode\(\))?\.matches\(R"(.*)"\)$/.exec(
      expr
    );
  if (asMatches) {
    const re = new RegExp(asMatches[1]);
    return value => re.test(value.toLowerCase());
  }
  const asStartsWith = /^request\.path\.lower\(\)\.urlDecode\(\)\.startsWith\(R"(.*)"\)$/.exec(
    expr
  );
  if (asStartsWith) {
    return value => value.toLowerCase().startsWith(asStartsWith[1]);
  }
  throw new Error(`unrecognized expression: ${expr}`);
}

describe('hostCondition', () => {
  test('matches per-node hosts on every cluster DNS name, with and without a port', () => {
    const expr = hostCondition('publicScan', dnsNames, undefined, 'scan')!;
    const matches = matchesFromExpr(expr);

    expect(matches('scan.sv-2.scratchd.network.canton.global')).toBe(true);
    expect(matches('scan.sv-2.scratchd.global.canton.network.digitalasset.com')).toBe(true);
    expect(matches('scan.sv-2.scratchd.network.canton.global:443')).toBe(true);
    expect(matches('SCAN.sv-2.scratchd.network.canton.global')).toBe(true);

    expect(matches('sv.sv-2.scratchd.network.canton.global')).toBe(false);
    expect(matches('scan.sv-2.scratcha.network.canton.global')).toBe(false);
    // the escaped dots must not act as wildcards
    expect(matches('scan.sv-2.scratchdXnetwork.canton.global')).toBe(false);
    // must be fully anchored
    expect(matches('evil.scan.sv-2.scratchd.network.canton.global')).toBe(false);
    expect(matches('scan.sv-2.scratchd.network.canton.global.evil.com')).toBe(false);
  });

  test('sequencer prefix regex matches all migration ids and both APIs', () => {
    const matches = matchesFromExpr(
      hostCondition('sequencer', dnsNames, undefined, 'sequencer-[0-9]+|sequencer-p2p-[0-9]+')!
    );

    expect(matches('sequencer-0.sv-1.scratchd.network.canton.global')).toBe(true);
    expect(matches('sequencer-12.sv-1.scratchd.network.canton.global')).toBe(true);
    expect(matches('sequencer-p2p-3.sv-1.scratchd.network.canton.global')).toBe(true);
    expect(matches('sequencer-0.sv-1.scratchd.global.canton.network.digitalasset.com:443')).toBe(
      true
    );

    expect(matches('sequencer.sv-1.scratchd.network.canton.global')).toBe(false);
    expect(matches('scan.sv-1.scratchd.network.canton.global')).toBe(false);
  });

  test('exact hostname is anchored and regex-escaped', () => {
    const matches = matchesFromExpr(
      hostCondition('publicScan', dnsNames, 'scan.sv-2.scratchd.network.canton.global')!
    );
    expect(matches('scan.sv-2.scratchd.network.canton.global')).toBe(true);
    expect(matches('scan.sv-2.scratchdXnetwork.canton.global')).toBe(false);
    expect(matches('scan.sv-3.scratchd.network.canton.global')).toBe(false);
  });

  test('is undefined when neither hostname nor prefix is given', () => {
    expect(hostCondition('anything', dnsNames)).toBeUndefined();
  });
});

describe('allowedPathsCondition', () => {
  test('narrows to the rate limited paths under the prefix', () => {
    const matches = matchesFromExpr(
      allowedPathsCondition('publicScan', scanRateLimits, '/api/scan', true)
    );

    expect(matches('/api/scan/v0/dso')).toBe(true);
    expect(matches('/api/scan/v0/dso/extra')).toBe(true);
    expect(matches('/api/scan/v0/not-rate-limited')).toBe(false);
    expect(matches('/registry/metadata/v1/info')).toBe(false);
  });

  test('narrows the token registry to its own paths', () => {
    const matches = matchesFromExpr(
      allowedPathsCondition('tokenRegistry', scanRateLimits, '/registry', true)
    );
    expect(matches('/registry/metadata/v1/info')).toBe(true);
    expect(matches('/api/scan/v0/dso')).toBe(false);
  });

  test('does not narrow when restrictToRateLimitedPaths is false', () => {
    // regression: the scan/registry rate limit prefixes all start with "/", so
    // narrowing a "/" prefix used to produce a scan-only regex that matched none of
    // the sequencer's gRPC paths, silently denying all sequencer traffic
    const matches = matchesFromExpr(allowedPathsCondition('sequencer', scanRateLimits, '/', false));

    expect(matches('/com.digitalasset.canton.sequencer.api.v30.SequencerService/Subscribe')).toBe(
      true
    );
    expect(matches('/api/scan/v0/dso')).toBe(true);
  });

  test('falls back to a prefix match when no rate limited path is under the prefix', () => {
    const matches = matchesFromExpr(
      allowedPathsCondition('other', scanRateLimits, '/api/sv', true)
    );
    expect(matches('/api/sv/v0/dso')).toBe(true);
    expect(matches('/api/scan/v0/dso')).toBe(false);
  });

  test('throws when the generated path expression is too long', () => {
    const manyPaths = Object.fromEntries(
      Array.from({ length: 200 }, (_unused, i) => [
        `/api/scan/v0/endpoint-with-a-fairly-long-name-${i}`,
        { name: `e${i}`, type: 'limited' },
      ])
    );
    expect(() =>
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      allowedPathsCondition('publicScan', { rateLimits: manyPaths } as any, '/api/scan', true)
    ).toThrow(new RegExp(`exceeds the ${MAX_SUBEXPRESSION_LENGTH} character limit`));
  });
});

describe('matchExpression', () => {
  test('combines path and host conditions', () => {
    expect(matchExpression('publicScan', 'PATH', 'HOST')).toBe('PATH && HOST');
  });

  test('omits the host condition when there is none', () => {
    expect(matchExpression('publicScan', 'PATH', undefined)).toBe('PATH');
  });
});

describe('ipWhitelistRuleChunks', () => {
  const ips = (n: number) => Array.from({ length: n }, (_unused, i) => `10.0.${i}.0/24`);

  test('respects the per-rule IP range limit', () => {
    const chunks = ipWhitelistRuleChunks(ips(25), 100);
    expect(chunks).toHaveLength(3);
    expect(chunks[0]).toHaveLength(MAX_IPS_PER_RULE);
    expect(chunks[2]).toHaveLength(5);
    expect(chunks.flat().sort()).toEqual(ips(25).sort());
  });

  test('deduplicates and sorts so rule contents are stable across runs', () => {
    expect(ipWhitelistRuleChunks(['10.0.2.0/24', '10.0.1.0/24', '10.0.2.0/24'], 100)).toEqual([
      ['10.0.1.0/24', '10.0.2.0/24'],
    ]);
  });

  test('refuses an empty whitelist rather than locking everything out', () => {
    expect(() => ipWhitelistRuleChunks([], 100)).toThrow(/every internal endpoint would be denied/);
  });

  test('refuses to exceed the per-policy rule budget', () => {
    expect(() =>
      ipWhitelistRuleChunks(ips(MAX_IP_WHITELIST_RULES * MAX_IPS_PER_RULE + 1), 100000)
    ).toThrow(/exceeds the 150 rule budget/);
  });

  test('refuses to overlap the throttle rule priority range', () => {
    expect(() => ipWhitelistRuleChunks(ips(50), 2)).toThrow(
      /would overlap the throttle rule priority range/
    );
  });
});

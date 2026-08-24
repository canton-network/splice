// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'fs';
import * as path from 'path';
import { describe, expect, test } from '@jest/globals';

import {
  exposedAudiences,
  parseSvPublicEndpoints,
  publicAudiences,
  svPublicIngressPathsByAudience,
  toIngressPath,
} from './svPublicEndpoints';

const svOpenApiFile = path.join(
  __dirname,
  '../../../../../apps/sv/src/main/openapi/sv-internal.yaml'
);

describe('the SV OpenAPI spec', () => {
  const content = fs.readFileSync(svOpenApiFile, 'utf-8');

  test('declares an x-public-audience for every sv_public endpoint', () => {
    const endpoints = parseSvPublicEndpoints(content);
    expect(endpoints.length).toBeGreaterThan(0);
    endpoints.forEach(endpoint => {
      expect(publicAudiences).toContain(endpoint.audience);
    });
  });

  test('exposes the expected paths per audience', () => {
    const paths = svPublicIngressPathsByAudience(content);
    expect(paths['validators']).toContain('/api/sv/v0/onboard/validator');
    expect(paths['validators']).toContain('/api/sv/v0/dso');
    expect(paths['sv-operators']).toContain('/api/sv/v0/migration-id');
    expect(paths['sv-operators']).toContain('/api/sv/v0/onboard/sv/status/*');
    const allPaths = exposedAudiences.flatMap(audience => paths[audience]);
    // endpoints with an audience of none must not be whitelisted
    expect(allPaths).not.toContain('/api/sv/v0/admin/domain/cometbft/status');
    expect(allPaths).not.toContain('/api/sv/v0/admin/domain/cometbft/json-rpc');
    // non-public endpoints must not be whitelisted
    expect(allPaths).not.toContain('/api/sv/v0/admin/sv/votes');
    expect(allPaths).not.toContain('/api/sv/readyz');
  });
});

describe('parseSvPublicEndpoints', () => {
  const spec = (extra: string) => `
openapi: 3.0.0
paths:
  /v0/foo:
    get:
      x-jvm-package: sv_public
${extra}
      operationId: getFoo
      responses:
        "200":
          description: ok
`;

  test('fails if an sv_public endpoint has no x-public-audience', () => {
    expect(() => parseSvPublicEndpoints(spec(''))).toThrow(/must declare x-public-audience/);
  });

  test('fails on an unknown x-public-audience', () => {
    expect(() => parseSvPublicEndpoints(spec('      x-public-audience: everyone'))).toThrow(
      /must declare x-public-audience/
    );
  });

  test('accepts a valid x-public-audience', () => {
    expect(parseSvPublicEndpoints(spec('      x-public-audience: validators'))).toEqual([
      { path: '/v0/foo', method: 'get', operationId: 'getFoo', audience: 'validators' },
    ]);
  });

  test('accepts an audience of none but does not whitelist the endpoint', () => {
    const content = spec('      x-public-audience: none');
    expect(parseSvPublicEndpoints(content)).toEqual([
      { path: '/v0/foo', method: 'get', operationId: 'getFoo', audience: 'none' },
    ]);
    const paths = svPublicIngressPathsByAudience(content);
    expect(exposedAudiences.flatMap(audience => paths[audience])).toEqual([]);
  });

  test('rejects x-public-audience on non-public endpoints', () => {
    const nonPublic = `
openapi: 3.0.0
paths:
  /v0/foo:
    get:
      x-jvm-package: sv_operator
      x-public-audience: validators
      operationId: getFoo
`;
    expect(() => parseSvPublicEndpoints(nonPublic)).toThrow(/only allowed on endpoints/);
  });
});

test('toIngressPath replaces path parameters with a wildcard', () => {
  expect(toIngressPath('/v0/onboard/sv/status/{candidate_party_id_or_name}')).toBe(
    '/api/sv/v0/onboard/sv/status/*'
  );
  expect(toIngressPath('/v0/dso')).toBe('/api/sv/v0/dso');
});

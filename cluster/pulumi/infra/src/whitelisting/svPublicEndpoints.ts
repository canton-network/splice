// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'fs';
import { load } from 'js-yaml';

export const svApiPathPrefix = '/api/sv';

export const publicAudiences = ['validators', 'sv-operators', 'none'] as const;
export type PublicAudience = (typeof publicAudiences)[number];

export const exposedAudiences = ['validators', 'sv-operators'] as const;
export type ExposedAudience = (typeof exposedAudiences)[number];

const httpMethods = ['get', 'put', 'post', 'delete', 'patch', 'head', 'options'];

export type SvPublicEndpoint = {
  path: string;
  method: string;
  operationId?: string;
  audience: PublicAudience;
};

function isPublicAudience(value: unknown): value is PublicAudience {
  return publicAudiences.includes(value as PublicAudience);
}

/**
 * Parses the SV OpenAPI spec and returns all endpoints that are exposed without
 * authentication (`x-jvm-package: sv_public`).
 *
 * Throws if an `sv_public` endpoint does not declare a valid `x-public-audience`,
 * or if a non-public endpoint declares one.
 */
export function parseSvPublicEndpoints(openApiContent: string): SvPublicEndpoint[] {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const spec = load(openApiContent) as any;
  const paths = spec?.paths || {};
  const endpoints: SvPublicEndpoint[] = [];
  const errors: string[] = [];
  for (const path of Object.keys(paths)) {
    for (const method of httpMethods) {
      const operation = paths[path]?.[method];
      if (!operation) {
        continue;
      }
      const audience = operation['x-public-audience'];
      const isPublic = operation['x-jvm-package'] === 'sv_public';
      if (!isPublic) {
        if (audience !== undefined) {
          errors.push(
            `${method.toUpperCase()} ${path}: x-public-audience is only allowed on endpoints with x-jvm-package: sv_public`
          );
        }
        continue;
      }
      if (!isPublicAudience(audience)) {
        errors.push(
          `${method.toUpperCase()} ${path}: sv_public endpoints must declare x-public-audience as one of ${publicAudiences.join(
            ', '
          )} but got ${JSON.stringify(audience)}`
        );
        continue;
      }
      endpoints.push({ path, method, operationId: operation.operationId, audience });
    }
  }
  if (errors.length > 0) {
    throw new Error(`Invalid SV OpenAPI public endpoint definitions:\n${errors.join('\n')}`);
  }
  return endpoints;
}

export function toIngressPath(openApiPath: string): string {
  const withWildcards = openApiPath.replace(/\{[^}]+\}/g, '*');
  return `${svApiPathPrefix}${withWildcards}`;
}

export function svPublicIngressPathsByAudience(
  openApiContent: string
): Record<ExposedAudience, string[]> {
  const endpoints = parseSvPublicEndpoints(openApiContent);
  return Object.fromEntries(
    exposedAudiences.map(audience => [
      audience,
      [
        ...new Set(endpoints.filter(e => e.audience === audience).map(e => toIngressPath(e.path))),
      ].sort(),
    ])
  ) as Record<ExposedAudience, string[]>;
}

export function readSvPublicIngressPathsByAudience(
  openApiFile: string
): Record<ExposedAudience, string[]> {
  return svPublicIngressPathsByAudience(fs.readFileSync(openApiFile, 'utf-8'));
}

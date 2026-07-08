// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as path from 'path';
import { z } from 'zod';

import { readAndParseYaml } from './configLoader';

const scanYamlPath = path.join(__dirname, '../../../../../apps/scan/src/main/openapi/scan.yaml');
const tokenRegistryYamlPaths = [
  // V1 Specs
  path.join(
    __dirname,
    '../../../../../token-standard/splice-api-token-metadata-v1/openapi/token-metadata-v1.yaml'
  ),
  path.join(
    __dirname,
    '../../../../../token-standard/splice-api-token-allocation-v1/openapi/allocation-v1.yaml'
  ),
  path.join(
    __dirname,
    '../../../../../token-standard/splice-api-token-allocation-instruction-v1/openapi/allocation-instruction-v1.yaml'
  ),
  path.join(
    __dirname,
    '../../../../../token-standard/splice-api-token-transfer-instruction-v1/openapi/transfer-instruction-v1.yaml'
  ),

  // V2 Specs
  path.join(
    __dirname,
    '../../../../../token-standard/splice-api-token-allocation-v2/openapi/allocation-v2.yaml'
  ),
  path.join(
    __dirname,
    '../../../../../token-standard/splice-api-token-allocation-instruction-v2/openapi/allocation-instruction-v2.yaml'
  ),
  path.join(
    __dirname,
    '../../../../../token-standard/splice-api-token-transfer-instruction-v2/openapi/transfer-instruction-v2.yaml'
  ),
];

const MinimalOpenApiSchema = z.object({ paths: z.object({}).catchall(z.unknown()).default({}) });

/**
 * Read scan.yaml OpenAPI paths into normalized `/api/scan...` endpoint prefixes.
 * Keep only the static prefix before the first `{...}` segment if the path contains parameters.
 * This preserves only the segment for which simple string prefix matching works.
 */
export function parseScanYamlEndpoints(): string[] {
  const yaml = MinimalOpenApiSchema.parse(readAndParseYaml(scanYamlPath));
  const paths = yaml.paths;

  const endpoints = new Set<string>();

  for (const path of Object.keys(paths)) {
    // Prepend /api/scan prefix
    let fullPath = '/api/scan' + path;

    // Strip to segment before first {
    const paramIndex = fullPath.indexOf('{');
    if (paramIndex !== -1) {
      // Find the / before the {
      const lastSlash = fullPath.lastIndexOf('/', paramIndex);
      fullPath = fullPath.substring(0, lastSlash);
    }

    endpoints.add(fullPath);
  }

  return Array.from(endpoints).sort();
}

/**
 * Read all token registry standard OpenAPI paths into normalized `/registry/...` endpoint prefixes.
 */
export function parseTokenRegistrySpecEndpoints(): string[] {
  const endpoints = new Set<string>();

  for (const yamlPath of tokenRegistryYamlPaths) {
    const yaml = MinimalOpenApiSchema.parse(readAndParseYaml(yamlPath));
    const paths = yaml.paths;

    for (let fullPath of Object.keys(paths)) {
      const paramIndex = fullPath.indexOf('{');
      if (paramIndex !== -1) {
        const lastSlash = fullPath.lastIndexOf('/', paramIndex);
        fullPath = fullPath.substring(0, lastSlash);
      }

      endpoints.add(fullPath);
    }
  }

  return Array.from(endpoints).sort();
}

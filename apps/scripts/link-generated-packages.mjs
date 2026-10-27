// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// Links build-generated packages (Daml TS codegen and OpenAPI clients) into
// node_modules so that they resolve like installed packages without being
// declared in package.json.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appsDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const nodeModules = path.join(appsDir, 'node_modules');
const config = JSON.parse(fs.readFileSync(path.join(appsDir, 'generated-packages.json'), 'utf8'));

function link(packageName, targetDir) {
  const linkPath = path.join(nodeModules, packageName);
  fs.mkdirSync(path.dirname(linkPath), { recursive: true });
  fs.rmSync(linkPath, { recursive: true, force: true });
  fs.symlinkSync(path.relative(path.dirname(linkPath), targetDir), linkPath, 'dir');
}

function readPackageName(dir) {
  return JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8')).name;
}

let linked = 0;
const skipped = [];
const missingAliases = [];

const damlJsDir = path.join(appsDir, config.damlJs.directory);
if (fs.existsSync(damlJsDir)) {
  for (const entry of fs.readdirSync(damlJsDir, { withFileTypes: true })) {
    if (!entry.isDirectory() || !fs.existsSync(path.join(damlJsDir, entry.name, 'package.json'))) continue;
    link(`@daml.js/${entry.name}`, path.join(damlJsDir, entry.name));
    linked++;
  }
  for (const [alias, dir] of Object.entries(config.damlJs.aliases)) {
    const target = path.join(damlJsDir, dir);
    if (!fs.existsSync(target)) {
      missingAliases.push(`@daml.js/${alias} -> ${dir}`);
      continue;
    }
    link(`@daml.js/${alias}`, target);
    linked++;
  }
} else {
  skipped.push(config.damlJs.directory);
}

for (const relDir of config.openApiClients) {
  const dir = path.resolve(appsDir, relDir);
  if (!fs.existsSync(path.join(dir, 'package.json'))) {
    skipped.push(relDir);
    continue;
  }
  const dist = path.join(dir, 'dist', 'index.js');
  const stale =
    !fs.existsSync(dist) || fs.statSync(dist).mtimeMs < fs.statSync(path.join(dir, 'package.json')).mtimeMs;
  if (stale) {
    execFileSync(path.join(nodeModules, '.bin', 'tsc'), ['-p', dir], { stdio: 'inherit' });
  }
  link(readPackageName(dir), dir);
  linked++;
}

console.log(`link-generated-packages: linked ${linked} packages`);
if (missingAliases.length > 0) {
  console.error(
    `link-generated-packages: generated-packages.json names versions that were not generated: ${missingAliases.join(', ')} (is the Daml version bump and the codegen in sync?)`
  );
  process.exit(1);
}
if (skipped.length > 0) {
  console.log(
    `link-generated-packages: not generated yet, skipped: ${skipped.join(', ')} (run the sbt codegen and npm install again)`
  );
}

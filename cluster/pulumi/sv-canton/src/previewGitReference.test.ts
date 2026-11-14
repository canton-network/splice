// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import type { MigrationInfo } from '@canton-network/splice-pulumi-common/src/domainMigration';
import { afterEach, beforeEach, describe, expect, jest, test } from '@jest/globals';

import {
  migrationFollowsReference,
  migrationsFollowingReference,
  normalizeGitReference,
  parsePreviewGitReference,
} from './previewGitReference';

const CURRENT = 'refs/heads/release-line-0.6.7';
const OTHER = 'refs/heads/release-line-0.4.25';
const TAG = 'refs/tags/cilr';

function migration(id: number, gitReference?: string): MigrationInfo {
  return {
    id,
    version: { type: 'remote', version: `0.${id}.0` },
    sequencer: { enableBftSequencer: true, dedicatedBftSequencerDb: true },
    ...(gitReference !== undefined
      ? {
          releaseReference: {
            repoUrl: 'https://github.com/DACH-NY/canton-network-internal',
            gitReference,
            pulumiStacksDir: 'cluster/stacks/prod',
            pulumiBaseDir: 'splice/cluster/pulumi',
            deploymentDir: 'cluster/deployment',
            spliceRoot: 'splice',
          },
        }
      : {}),
  };
}

const active = migration(4, CURRENT);

describe('normalizeGitReference', () => {
  test.each([
    ['release-line-0.6.7', CURRENT],
    ['origin/release-line-0.6.7', CURRENT],
    ['  release-line-0.6.7 ', CURRENT],
    [CURRENT, CURRENT],
    [TAG, TAG],
  ])('normalizes %p to %p', (input, expected) => {
    expect(normalizeGitReference(input)).toBe(expected);
  });
});

describe('parsePreviewGitReference', () => {
  test('returns undefined when the env var is unset or blank', () => {
    expect(parsePreviewGitReference(undefined)).toBeUndefined();
    expect(parsePreviewGitReference('')).toBeUndefined();
    expect(parsePreviewGitReference('   ')).toBeUndefined();
  });

  test('normalizes a set value', () => {
    expect(parsePreviewGitReference('release-line-0.6.7')).toBe(CURRENT);
  });
});

describe('migrationFollowsReference', () => {
  test('keeps a migration tracking the current branch', () => {
    expect(migrationFollowsReference(migration(4, CURRENT), CURRENT, active)).toBe(true);
  });

  test('skips a migration tracking a different branch', () => {
    expect(migrationFollowsReference(migration(3, OTHER), CURRENT, active)).toBe(false);
  });

  test('inherits the active reference when releaseReference is missing', () => {
    expect(migrationFollowsReference(migration(5), CURRENT, active)).toBe(true);
    expect(migrationFollowsReference(migration(5), CURRENT, migration(4, OTHER))).toBe(false);
  });

  test('keeps a migration when neither it nor the active migration has a reference', () => {
    expect(migrationFollowsReference(migration(5), CURRENT, migration(4))).toBe(true);
  });

  test('never skips tag references', () => {
    expect(migrationFollowsReference(migration(3, TAG), CURRENT, active)).toBe(true);
    expect(migrationFollowsReference(migration(3, OTHER), TAG, active)).toBe(true);
  });

  test('normalizes bare branch names before comparing', () => {
    expect(migrationFollowsReference(migration(4, CURRENT), 'release-line-0.6.7', active)).toBe(
      true
    );
  });
});

describe('migrationsFollowingReference', () => {
  let errorSpy: jest.SpiedFunction<typeof console.error>;
  const all = [active, migration(3, OTHER), migration(5), migration(2, TAG)];

  beforeEach(() => {
    errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    errorSpy.mockRestore();
  });

  test('returns all migrations when the env var is unset', () => {
    expect(migrationsFollowingReference(all, undefined, active)).toEqual(all);
  });

  test('filters out migrations tracking a different branch and logs them', () => {
    const result = migrationsFollowingReference(all, CURRENT, active);
    expect(result.map(m => m.id)).toEqual([4, 5, 2]);
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('Skipping migration 3'));
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining(OTHER));
  });
});

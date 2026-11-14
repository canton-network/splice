// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// Pure helpers (no pulumi runtime / config side effects) so they can be unit tested.
import type { MigrationInfo } from '@canton-network/splice-pulumi-common/src/domainMigration';

export const PREVIEW_GIT_REFERENCE_ENV = 'SPLICE_PREVIEW_GIT_REFERENCE';

// Normalises a branch name or git ref to the `refs/heads/<branch>` form used by
// `releaseReference.gitReference` in the cluster config. Fully qualified refs (e.g. tags) are kept as-is.
export function normalizeGitReference(ref: string): string {
  const trimmed = ref.trim();
  if (trimmed.startsWith('refs/')) {
    return trimmed;
  }
  return `refs/heads/${trimmed.replace(/^origin\//, '')}`;
}

// Parses the raw value of SPLICE_PREVIEW_GIT_REFERENCE; undefined/blank means "no filtering".
export function parsePreviewGitReference(raw: string | undefined): string | undefined {
  return raw && raw.trim() !== '' ? normalizeGitReference(raw) : undefined;
}

function isBranchReference(ref: string): boolean {
  return ref.startsWith('refs/heads/');
}

// Decides whether `migration` is deployed from `currentRef` by the operator and is therefore
// relevant to a preview run from that ref (see deployment/src/stacks/migration.ts):
// - migrations with a `releaseReference` are compared directly against it,
// - migrations without one inherit the active migration's reference,
// - only branch refs (`refs/heads/...`) are comparable; tags or missing refs are never skipped.
export function migrationFollowsReference(
  migration: MigrationInfo,
  currentRef: string,
  activeMigration: MigrationInfo
): boolean {
  const trackedRef = (migration.releaseReference ?? activeMigration.releaseReference)?.gitReference;
  if (trackedRef === undefined) {
    return true;
  }
  const normalizedTracked = normalizeGitReference(trackedRef);
  const normalizedCurrent = normalizeGitReference(currentRef);
  if (!isBranchReference(normalizedTracked) || !isBranchReference(normalizedCurrent)) {
    return true;
  }
  return normalizedTracked === normalizedCurrent;
}

function describeVersion(migration: MigrationInfo): string {
  return migration.version.type === 'remote' ? migration.version.version : 'local';
}

// Filters `all` with `migrationFollowsReference` when `currentRef` is set, logging each skipped
// migration together with the reference it tracks. Returns `all` unchanged when `currentRef` is undefined.
export function migrationsFollowingReference(
  all: MigrationInfo[],
  currentRef: string | undefined,
  activeMigration: MigrationInfo
): MigrationInfo[] {
  if (currentRef === undefined) {
    console.error(
      `${PREVIEW_GIT_REFERENCE_ENV} is not set, running for all ${all.length} migrations`
    );
    return all;
  }
  console.error(`Only running for migrations following ${currentRef}`);
  return all.filter(migration => {
    const follows = migrationFollowsReference(migration, currentRef, activeMigration);
    if (!follows) {
      const trackedRef =
        migration.releaseReference?.gitReference ?? '<inherited from active migration>';
      console.error(
        `Skipping migration ${migration.id} (version ${describeVersion(migration)}): ` +
          `it follows ${trackedRef}, not ${currentRef}`
      );
    }
    return follows;
  });
}

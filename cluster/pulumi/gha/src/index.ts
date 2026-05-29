// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { GcpProject } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/gcpConfig';

import { ghaConfig, isSpliceCluster } from './config';
import { installController } from './controller';
import { installDockerRegistryMirror } from './dockerMirror';
import { installGithubRepo } from './github';
import { manageMainnetHistoryDumpsUser } from './mainnetHistoryDumpsUser';
import { installRunnerScaleSets } from './runners';

installDockerRegistryMirror();
for (const repo of ghaConfig.githubRepos) {
  console.error(`Configuring GHA runner for repository: ${repo}`);
  const runnersNamespaceName = `gha-runners-${repo}`;
  const controller = installController(repo, runnersNamespaceName);
  installRunnerScaleSets(controller, runnersNamespaceName, repo);
  installGithubRepo(repo);
}

if (ghaConfig.mainnetHistoryDumpsUser) {
  if (!isSpliceCluster) {
    throw new Error(`mainnetHistoryDumpsUser is only allowed on da-cn-splice, got ${GcpProject}`);
  }
  manageMainnetHistoryDumpsUser(GcpProject, ghaConfig.mainnetHistoryDumpsUser);
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ghaConfig } from './config';
import { installController } from './controller';
import { installDockerRegistryMirror } from './dockerMirror';
import { installRunnerScaleSets } from './runners';

installDockerRegistryMirror();
for (const repo of ghaConfig.githubRepos) {
  console.error(`Configuring GHA runner for repository: ${repo}`);
  const controller = installController(repo);
  installRunnerScaleSets(controller, repo);
}

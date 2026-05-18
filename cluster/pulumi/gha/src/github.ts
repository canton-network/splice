// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as github from '@pulumi/github';

import { ghaConfig } from './config';

export function installGithubRepo(repo: string): void {
  const githubRepo = `${ghaConfig.githubOrg}/${repo}`;
  new github.ActionsVariable('example-variable', {
    repository: githubRepo,
    variableName: 'test_var',
    value: 'test_value',
  });
}

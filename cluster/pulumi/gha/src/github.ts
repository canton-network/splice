// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as github from '@pulumi/github';
import { DockerConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/dockerConfig';

import { ghaConfig } from './config';

export function installGithubRepo(repo: string): void {
  const githubRepo = `${ghaConfig.githubOrg}/${repo}`;

  DockerConfig.fetchCredentialsFromSecret('artifactory-keys').apply(creds => {
    new github.ActionsVariable('example-variable', {
      repository: githubRepo,
      variableName: 'test_var',
      value: creds.username,
    });
    new github.ActionsSecret('example-secret', {
      repository: githubRepo,
      secretName: 'test_secret',
      value: creds.password,
    });
  });
}

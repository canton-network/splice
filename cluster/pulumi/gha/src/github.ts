// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as github from '@pulumi/github';
import { DockerConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/dockerConfig';

import { ghaConfig } from './config';

export function installGithubRepo(repo: string): void {

  const orgProvider = new github.Provider("canton-network-provider", {
    owner: ghaConfig.githubOrg.replaceAll('https://github.com/', ''), // FIXME: This is ugly. change the config to not include the url prefix
  });

  DockerConfig.fetchCredentialsFromSecret('artifactory-keys').apply(creds => {
    new github.ActionsVariable('example-variable', {
      repository: repo,
      variableName: 'test_var',
      value: creds.username,
    }, { provider: orgProvider });
    new github.ActionsSecret('example-secret', {
      repository: repo,
      secretName: 'test_secret',
      value: creds.password,
    }, { provider: orgProvider });
  });
}

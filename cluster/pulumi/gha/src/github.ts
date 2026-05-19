// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as github from '@pulumi/github';
import { DockerConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/dockerConfig';

import { ghaConfig } from './config';

export function installGithubRepo(repo: string): void {
  const orgProvider = new github.Provider('canton-network-provider', {
    owner: ghaConfig.githubOrg.replaceAll('https://github.com/', ''), // TODO(#5570): after we change the config to be just the org, remove the replaceAll
  });

  DockerConfig.fetchCredentialsFromSecret('artifactory-keys').apply(creds => {
    new github.ActionsVariable(
      'artifactory-user',
      {
        repository: repo,
        variableName: 'ARTIFACTORY_USER',
        value: creds.username,
      },
      { provider: orgProvider }
    );
    new github.ActionsSecret(
      'artifactory-password',
      {
        repository: repo,
        secretName: 'ARTIFACTORY_PASSWORD',
        value: creds.password,
      },
      { provider: orgProvider }
    );
  });
}

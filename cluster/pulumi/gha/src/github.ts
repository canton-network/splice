// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as github from '@pulumi/github';
import { DockerConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/dockerConfig';

import { ghaConfig } from './config';

export function installGithubRepo(repo: string): void {
  const orgProvider = new github.Provider('canton-network-provider', {
    owner: ghaConfig.githubOrg.replaceAll('https://github.com/', ''), // TODO(#5570): after we change the config to be just the org, remove the replaceAll
  });

  // A bit ugly that we reuse this straight from DockerConfig, but we plan to
  // retire artifactory altogether soon, so we don't bother cleaning this up.
  const creds = DockerConfig.fetchCredentialsFromSecret('artifactory-keys');
  new github.ActionsVariable(
    `artifactory-user-${repo}`,
    {
      repository: repo,
      variableName: 'ARTIFACTORY_USER',
      value: creds.apply(creds => creds.username),
    },
    { provider: orgProvider }
  );
  new github.ActionsSecret(
    `artifactory-password-${repo}`,
    {
      repository: repo,
      secretName: 'ARTIFACTORY_PASSWORD',
      value: creds.apply(creds => creds.password),
    },
    { provider: orgProvider }
  );
}

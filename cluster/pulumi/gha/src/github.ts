// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as github from '@pulumi/github';
import { DockerConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/dockerConfig';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';

import { ghaConfig } from './config';

function copySecretToGithubActionsSecret(
  secretName: string,
  githubSecretName: string,
  repo: string,
  provider: github.Provider
): void {
  const secret = getSecretVersionOutput({ secret: secretName });
  new github.ActionsSecret(
    `${secretName}-${repo}`,
    {
      repository: repo,
      secretName: githubSecretName,
      value: secret.secretData,
    },
    { provider }
  );
}

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

  const auth0TestsManagementApi = getSecretVersionOutput({ secret: 'auth0-tests-management-api' });
  new github.ActionsSecret(
    `auth0-tests-management-api-client-id-${repo}`,
    {
      repository: repo,
      secretName: 'AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID',
      value: auth0TestsManagementApi.apply(api => JSON.parse(api.secretData).clientId),
    },
    { provider: orgProvider }
  );
  new github.ActionsSecret(
    `auth0-tests-management-api-client-secret-${repo}`,
    {
      repository: repo,
      secretName: 'AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET',
      value: auth0TestsManagementApi.apply(api => JSON.parse(api.secretData).secret),
    },
    { provider: orgProvider }
  );

  copySecretToGithubActionsSecret(
    'auth0-validator-audience',
    'AUTH0_VALIDATOR_AUDIENCE',
    repo,
    orgProvider
  );
  copySecretToGithubActionsSecret(
    'compose-validator-web-ui-password',
    'COMPOSE_VALIDATOR_WEB_UI_PASSWORD',
    repo,
    orgProvider
  );
}

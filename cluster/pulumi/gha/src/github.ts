// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
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

function createSaKeySecret(
  saName: string,
  githubSecretName: string,
  repo: string,
  provider: github.Provider
): void {
  const dataExportTestsKey = new gcp.serviceaccount.Key(`${saName}-key-${repo}`, {
    serviceAccountId: `${saName}@da-cn-splice.iam.gserviceaccount.com`,
    privateKeyType: 'TYPE_GOOGLE_CREDENTIALS_FILE',
  });
  new github.ActionsSecret(
    `${saName}-key-secret-${repo}`,
    {
      repository: repo,
      secretName: githubSecretName,
      value: dataExportTestsKey.privateKey.apply(k => Buffer.from(k, 'base64').toString('utf-8')),
    },
    { provider: provider }
  );
}

export function installGithubRepo(repo: string): void {
  const orgProvider = new github.Provider(`canton-network-provider-${repo}`, {
    owner: ghaConfig.githubOrg,
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
    'AUTH0_TESTS_VALIDATOR_AUDIENCE',
    repo,
    orgProvider
  );
  copySecretToGithubActionsSecret(
    'compose-validator-web-ui-password',
    'COMPOSE_VALIDATOR_WEB_UI_PASSWORD',
    repo,
    orgProvider
  );

  createSaKeySecret(
    'splice-data-export-tests',
    'GCP_DATA_EXPORT_INTEGRATION_TEST_SERVICE_ACCOUNT_CREDENTIALS',
    repo,
    orgProvider
  );
  createSaKeySecret(
    'splice-kms-integration-test',
    'GCP_KMS_INTEGRATION_TEST_SERVICE_ACCOUNT_CREDENTIALS',
    repo,
    orgProvider
  );
}

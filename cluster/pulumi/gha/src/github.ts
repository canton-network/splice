// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as github from '@pulumi/github';
import * as pulumi from '@pulumi/pulumi';
import { DockerConfig } from '@canton-network/splice-pulumi-common/src/dockerConfig';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';

import { ghaConfig } from './config';

// One GitHub secret per configured suffix, all with the same value.
// The empty suffix keeps the original resource names so existing secrets are not replaced.
function createActionsSecrets(
  resourceName: string,
  githubSecretName: string,
  value: pulumi.Input<string>,
  repo: string,
  provider: github.Provider
): void {
  for (const suffix of ghaConfig.secretSuffixes) {
    new github.ActionsSecret(
      suffix === '' ? `${resourceName}-${repo}` : `${resourceName}-${repo}-${suffix}`,
      {
        repository: repo,
        secretName: `${githubSecretName}${suffix}`,
        value,
      },
      { provider }
    );
  }
}

function copySecretToGithubActionsSecret(
  secretName: string,
  githubSecretName: string,
  repo: string,
  provider: github.Provider
): void {
  const secret = getSecretVersionOutput({ secret: secretName });
  createActionsSecrets(secretName, githubSecretName, secret.secretData, repo, provider);
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
  createActionsSecrets(
    `${saName}-key-secret`,
    githubSecretName,
    dataExportTestsKey.privateKey.apply(k => Buffer.from(k, 'base64').toString('utf-8')),
    repo,
    provider
  );
}

export function installGithubRepo(repo: string): void {
  const orgProvider = new github.Provider(`canton-network-provider-${repo}`, {
    owner: ghaConfig.githubOrg,
  });

  const auth0TestsManagementApi = getSecretVersionOutput({ secret: 'auth0-tests-management-api' });
  createActionsSecrets(
    'auth0-tests-management-api-client-id',
    'AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID',
    auth0TestsManagementApi.apply(api => JSON.parse(api.secretData).clientId),
    repo,
    orgProvider
  );
  createActionsSecrets(
    'auth0-tests-management-api-client-secret',
    'AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET',
    auth0TestsManagementApi.apply(api => JSON.parse(api.secretData).secret),
    repo,
    orgProvider
  );

  if (ghaConfig.forkPrRequiredCommit) {
    new github.ActionsVariable(
      `fork-pr-required-commit-${repo}`,
      {
        repository: repo,
        variableName: 'FORK_PR_REQUIRED_COMMIT',
        value: ghaConfig.forkPrRequiredCommit,
      },
      { provider: orgProvider }
    );
  }

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

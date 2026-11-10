// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as github from '@pulumi/github';
import * as pulumi from '@pulumi/pulumi';
import { collectResources } from '@canton-network/splice-pulumi-common/src/test';
import { afterEach, beforeEach, expect, jest, test } from '@jest/globals';

import { ghaConfig } from './config';
import { installGithubRepo } from './github';

jest.mock('./config', () => ({
  __esModule: true,
  ghaConfig: { githubOrg: 'test-org', secretSuffixes: [''] },
}));
const mockGhaConfig = ghaConfig as { secretSuffixes: string[]; forkPrRequiredCommit?: string };

beforeEach(async () => {
  await pulumi.runtime.setMocks({
    newResource(args) {
      const state =
        args.type === 'gcp:serviceaccount/key:Key'
          ? { ...args.inputs, privateKey: Buffer.from(`key-of-${args.name}`).toString('base64') }
          : args.inputs;
      return { id: `mock:${args.name}`, state };
    },
    call(args) {
      return args.token === 'gcp:secretmanager/getSecretVersion:getSecretVersion'
        ? {
            ...args.inputs,
            secretData: JSON.stringify({ clientId: 'client-id', secret: 'client-secret' }),
          }
        : args.inputs;
    },
  });
});

afterEach(async () => {
  await pulumi.runtime.disconnect();
});

function outputValue<T>(output: pulumi.Output<T>): Promise<T> {
  return new Promise(resolve => output.apply(resolve));
}

async function resourceName(r: pulumi.Resource): Promise<string> {
  return (await outputValue(r.urn)).split('::').pop()!;
}

type Installed = {
  secrets: { name: string; secretName: string; repository: string; value?: string }[];
  variables: { name: string; repository: string; variableName: string; value: string }[];
  saKeys: number;
};

async function install(): Promise<Installed> {
  const [, resources] = await collectResources(() => installGithubRepo('splice'));
  const secrets = await Promise.all(
    resources.filter(github.ActionsSecret.isInstance).map(async s => ({
      name: await resourceName(s),
      secretName: await outputValue(s.secretName),
      repository: await outputValue(s.repository),
      value: await outputValue(s.value),
    }))
  );
  const variables = await Promise.all(
    resources.filter(github.ActionsVariable.isInstance).map(async v => ({
      name: await resourceName(v),
      repository: await outputValue(v.repository),
      variableName: await outputValue(v.variableName),
      value: await outputValue(v.value),
    }))
  );
  return { secrets, variables, saKeys: resources.filter(gcp.serviceaccount.Key.isInstance).length };
}

// The pre-existing resource names; changing them would make pulumi replace (and thereby delete) the secrets.
const originalSecrets = [
  ['auth0-tests-management-api-client-id-splice', 'AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID'],
  ['auth0-tests-management-api-client-secret-splice', 'AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET'],
  ['auth0-validator-audience-splice', 'AUTH0_TESTS_VALIDATOR_AUDIENCE'],
  ['compose-validator-web-ui-password-splice', 'COMPOSE_VALIDATOR_WEB_UI_PASSWORD'],
  [
    'splice-data-export-tests-key-secret-splice',
    'GCP_DATA_EXPORT_INTEGRATION_TEST_SERVICE_ACCOUNT_CREDENTIALS',
  ],
  [
    'splice-kms-integration-test-key-secret-splice',
    'GCP_KMS_INTEGRATION_TEST_SERVICE_ACCOUNT_CREDENTIALS',
  ],
];

test('default config keeps the original secrets and sets no variable', async () => {
  mockGhaConfig.secretSuffixes = [''];
  mockGhaConfig.forkPrRequiredCommit = undefined;
  const { secrets, variables } = await install();

  expect(secrets.map(s => [s.name, s.secretName])).toEqual(originalSecrets);
  expect(variables).toEqual([]);
});

test('secret suffixes create identical copies under suffixed names', async () => {
  mockGhaConfig.secretSuffixes = ['', '_V2'];
  mockGhaConfig.forkPrRequiredCommit = undefined;
  const { secrets, saKeys } = await install();

  const byName = new Map(secrets.map(s => [s.secretName, s]));
  expect(byName.size).toBe(2 * originalSecrets.length);
  for (const [resourceName, secretName] of originalSecrets) {
    const original = byName.get(secretName)!;
    const copy = byName.get(`${secretName}_V2`)!;
    expect(original.name).toBe(resourceName);
    expect(copy.name).toBe(`${resourceName}-_V2`);
    expect(original.value).toBeTruthy();
    expect(copy.value).toEqual(original.value);
    expect(copy.repository).toBe('splice');
  }
  // One service account key per SA and repo, shared by all suffixed secrets.
  expect(saKeys).toBe(2);
});

test('forkPrRequiredCommit sets FORK_PR_REQUIRED_COMMIT', async () => {
  mockGhaConfig.secretSuffixes = [''];
  mockGhaConfig.forkPrRequiredCommit = '8847ce5241db960e9437778a79c26325d0b6567c';
  const { variables } = await install();

  expect(variables).toEqual([
    {
      name: 'fork-pr-required-commit-splice',
      repository: 'splice',
      variableName: 'FORK_PR_REQUIRED_COMMIT',
      value: '8847ce5241db960e9437778a79c26325d0b6567c',
    },
  ]);
});

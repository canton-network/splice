// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import { expect, test } from '@jest/globals';
import { collectResources } from '@lfdecentralizedtrust/splice-pulumi-common/src/test';

import { manageMainnetHistoryDumpsUser } from './mainnetHistoryDumpsUser';

test('manageMainnetHistoryDumpsUser creates SA, bucket binding, and one WIF binding per repo', async () => {
  await pulumi.runtime.setMocks({
    newResource(args) {
      // For service accounts, synthesize a realistic email + fully-qualified name
      // so downstream pulumi.interpolate values resolve cleanly.
      if (args.type === 'gcp:serviceaccount/account:Account') {
        const email = `${args.inputs.accountId}@example.iam.gserviceaccount.com`;
        return {
          id: `mock:${args.name}`,
          state: {
            ...args.inputs,
            email,
            name: `projects/test-project/serviceAccounts/${email}`,
          },
        };
      }
      return { id: `mock:${args.name}`, state: args.inputs };
    },
    call(args) {
      return args.inputs;
    },
  });

  try {
    const repos = ['canton-network/splice', 'DACH-NY/canton-network-internal'];
    const [, resources] = await collectResources(() => {
      manageMainnetHistoryDumpsUser('test-project', {
        bucket: 'test-bucket',
        wifProjectNumber: '123456789',
        wifPoolId: 'github-pool',
        githubRepositories: repos,
      });
    });

    const accounts = resources.filter(r => gcp.serviceaccount.Account.isInstance(r));
    const bucketBindings = resources.filter(r => gcp.storage.BucketIAMMember.isInstance(r));
    const wifBindings = resources.filter(r => gcp.serviceaccount.IAMMember.isInstance(r));

    // 1 SA + 1 bucket binding + N WIF bindings (one per repo)
    expect(accounts).toHaveLength(1);
    expect(bucketBindings).toHaveLength(1);
    expect(wifBindings).toHaveLength(repos.length);
  } finally {
    await pulumi.runtime.disconnect();
  }
});

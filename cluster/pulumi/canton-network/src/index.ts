// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { Auth0ClientType, getAuth0Config, Auth0Fetch } from '@canton-network/splice-pulumi-common';

import { installClusterVersion } from './clusterVersion';
import { installCluster } from './installCluster';
import { scheduleLoadGenerator } from './scheduleLoadGenerator';

async function auth0CacheAndInstallCluster(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  installClusterVersion();

  const dso = await installCluster(auth0Fetch);

  await auth0Fetch.saveAuth0Cache();

  return (await dso?.allSvs)?.map(sv => ({
    nodeName: sv.nodeName,
    databaseInstanceName: sv.appsPostgres.databaseId,
    databaseSecretName: sv.appsPostgres.secretName,
  }));
}

async function main() {
  const auth0FetchOutput = getAuth0Config(Auth0ClientType.MAINSTACK);

  const svs = auth0FetchOutput.apply(async auth0Fetch => {
    const svs = await auth0CacheAndInstallCluster(auth0Fetch);

    scheduleLoadGenerator(auth0Fetch, []);

    return svs;
  });

  return svs;
}

export const svs = pulumi.output(main());

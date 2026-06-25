// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  appsAffinityAndTolerations,
  CnInput,
  ExactNamespace,
  standardStorageClassName,
} from '@canton-network/splice-pulumi-common';
import { BitnamiPostgres } from '@canton-network/splice-pulumi-common/src/postgres';

import { multiValidatorConfig } from './config';

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  dependsOn: CnInput<pulumi.Resource>[]
): BitnamiPostgres {
  if (!multiValidatorConfig) {
    throw new Error('multiValidator config must be set when they are enabled');
  }
  const config = multiValidatorConfig!;

  return new BitnamiPostgres(xns, name, {
    secretName: `${name}-secret`,
    volumeSize: config.postgresPvcSize,
    storageClass: standardStorageClassName,
    maxConnections: 1000,
    resources: config.resources?.postgres,
    affinityAndTolerations: appsAffinityAndTolerations,
    dependsOn,
  });
}

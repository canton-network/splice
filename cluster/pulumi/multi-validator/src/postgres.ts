// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  activeVersion,
  CnInput,
  ExactNamespace,
  spliceConfig,
  standardStorageClassName,
} from '@canton-network/splice-pulumi-common';
import {
  installPasswordWithParent,
  SplicePostgres,
} from '@canton-network/splice-pulumi-common/src/postgres';

import { multiValidatorConfig } from './config';

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  dependsOn: CnInput<pulumi.Resource>[]
): SplicePostgres {
  const secretName = `${name}-secret`;

  if (!multiValidatorConfig) {
    throw new Error('multiValidator config must be set when they are enabled');
  }
  const config = multiValidatorConfig!;

  return new SplicePostgres(
    xns,
    name,
    parent => installPasswordWithParent(parent, xns, name, secretName),
    config.postgres,
    {
      db: {
        volumeSize: config.postgresPvcSize,
        maxConnections: 1000,
        volumeStorageClass: standardStorageClassName,
        pvcTemplateName: 'pg-data-hd',
      },
      resources: config.resources?.postgres,
    },
    true,
    !spliceConfig.pulumiProjectConfig.cloudSql.protected,
    activeVersion,
    false,
    dependsOn
  );
}

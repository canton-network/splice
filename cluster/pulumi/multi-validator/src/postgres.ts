// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  activeVersion,
  appsAffinityAndTolerations,
  CnInput,
  ExactNamespace,
  spliceConfig,
  standardStorageClassName,
} from '@canton-network/splice-pulumi-common';
import { installSplicePostgres, Postgres } from '@canton-network/splice-pulumi-common/src/postgres';

import { multiValidatorConfig } from './config';

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  dependsOn: CnInput<pulumi.Resource>[]
): Postgres {
  const secretName = `${name}-secret`;

  if (!multiValidatorConfig) {
    throw new Error('multiValidator config must be set when they are enabled');
  }
  const config = multiValidatorConfig!;

  return installSplicePostgres(
    xns,
    name,
    secretName,
    config.postgres,
    activeVersion,
    {},
    {
      db: {
        volumeSize: config.postgresPvcSize,
        maxConnections: 1000,
        volumeStorageClass: standardStorageClassName,
        pvcTemplateName: 'pg-data-hd',
      },
      resources: config.resources?.postgres,
      appsAffinityAndTolerations,
    },
    true, // overrideDbSizeFromValues
    false, // useInfraAffinityAndTolerations
    {
      dependsOn,
      ...(spliceConfig.pulumiProjectConfig.replacePostgresStatefulSetOnChanges
        ? {
            replaceOnChanges: ['*'],
            deleteBeforeReplace: true,
          }
        : {}),
    }
  );
}

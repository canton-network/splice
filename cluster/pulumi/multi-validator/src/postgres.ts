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
import { SplicePostgres } from '@canton-network/splice-pulumi-common/src/postgres';

import { hyperdiskSupportConfig } from '../../common/src/config/hyperdiskSupportConfig';
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
    secretName,
    {
      db: {
        volumeSize: config.postgresPvcSize,
        maxConnections: 1000,
        ...(hyperdiskSupportConfig.hyperdiskSupport.enabled
          ? {
              volumeStorageClass: standardStorageClassName,
              pvcTemplateName: 'pg-data-hd',
            }
          : {}),
      },
      resources: config.resources?.postgres,
    },
    true,
    !spliceConfig.pulumiProjectConfig.cloudSql.protected,
    activeVersion,
    false,
    spliceConfig.pulumiProjectConfig.splicePostgresHelmMigrationConfig
      .importDataFromSplicePostgresHelmChart,
    dependsOn
  );
}

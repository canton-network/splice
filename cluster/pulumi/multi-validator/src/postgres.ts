// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import {
  activeVersion,
  appsAffinityAndTolerations,
  CnInput,
  ExactNamespace,
  InstalledHelmChart,
  installPostgresPasswordSecret,
  installSpliceRunbookHelmChart,
  spliceConfig,
  standardStorageClassName,
  createVolumeSnapshot,
} from '@canton-network/splice-pulumi-common';

import { hyperdiskSupportConfig } from '../../common/src/config/hyperdiskSupportConfig';
import { multiValidatorConfig } from './config';

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  dependsOn: CnInput<pulumi.Resource>[]
): InstalledHelmChart {
  const password = new random.RandomPassword(`${xns.logicalName}-${name}-passwd`, {
    length: 16,
    overrideSpecial: '_%@',
    special: true,
  }).result;
  const secretName = `${name}-secret`;
  const passwordSecret = installPostgresPasswordSecret(xns, password, secretName);

  if (!multiValidatorConfig) {
    throw new Error('multiValidator config must be set when they are enabled');
  }
  const config = multiValidatorConfig!;

  return installSpliceRunbookHelmChart(
    xns,
    name,
    'splice-postgres',
    {
      persistence: { secretName },
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
      appsAffinityAndTolerations,
    },
    activeVersion,
    {
      dependsOn: [passwordSecret, ...dependsOn],
      ...(spliceConfig.pulumiProjectConfig.replacePostgresStatefulSetOnChanges
        ? {
            replaceOnChanges: ['*'],
            deleteBeforeReplace: true,
          }
        : {}),
    }
  );
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
  sanitizedForPostgres,
  svUserIds,
} from '@canton-network/splice-pulumi-common';
import {
  SynchronizerNodes,
  valuesForSvValidatorApp,
} from '@canton-network/splice-pulumi-common-sv';
import { SvConfig } from '@canton-network/splice-pulumi-common-sv/src/config';
import { installValidatorApp } from '@canton-network/splice-pulumi-common-validator/src/validator';
import { spliceConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import { Postgres } from '@canton-network/splice-pulumi-common/src/postgres';
import { Resource } from '@pulumi/pulumi';

import { persistenceConfig } from './persistence';
import { internalScanUrl } from './scan';

export async function installValidator(
  postgres: Postgres,
  xns: ExactNamespace,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  svConfig: SvConfig,
  backupConfigSecret: Resource | undefined,
  sv: SynchronizerNodes,
  svApp: Resource,
  scan: Resource
): Promise<Resource> {
  const validatorDbName = `validator_${sanitizedForPostgres(svConfig.nodeName)}`;
  const commonValidatorAppValues = valuesForSvValidatorApp(
    decentralizedSynchronizerMigrationConfig,
    svConfig
  );

  return await installValidatorApp({
    xns,
    ...commonValidatorAppValues,
    validatorWalletUsers: svUserIds(svConfig.auth0Client.getCfg()).apply(ids =>
      ids.concat(svConfig.validatorWalletUser ? [svConfig.validatorWalletUser] : [])
    ),
    dependencies: [],
    disableAllocateLedgerApiUserParty: true,
    topupConfig: svConfig.topupConfig,
    backupConfig:
      svConfig.periodicBackupConfig && backupConfigSecret
        ? {
            config: svConfig.periodicBackupConfig,
            secret: backupConfigSecret,
          }
        : undefined,
    persistenceConfig: persistenceConfig(postgres, validatorDbName),
    extraDependsOn: spliceConfig.pulumiProjectConfig.interAppsDependencies
      ? [svApp, postgres, scan]
      : [postgres],
    svValidator: true,
    participantAddress: sv.participant.internalClusterAddress,
    scanAddress: internalScanUrl(svConfig),
    auth0Client: svConfig.auth0Client,
    auth0ValidatorAppName: svConfig.auth0ValidatorAppName,
    sweep: svConfig.sweep,
    nodeIdentifier: svConfig.onboardingName,
    logLevel: svConfig.logging?.appsLogLevel,
    logAsync: svConfig.logging?.appsAsync,
    apiRequestLogLevel: svConfig.logging?.apiRequestLogLevel,
    additionalJvmOptions: svConfig.validatorApp?.additionalJvmOptions || '',
    resources: svConfig.validatorApp?.resources,
    version: svConfig.version,
  });
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as postgres from '@canton-network/splice-pulumi-common/src/postgres';
import {
  activeVersion,
  Auth0Config,
  auth0UserNameEnvVarSource,
  BucketConfig,
  ChartValues,
  checkKeyDownloadForPeriodicBackups,
  ExactNamespace,
  getAdditionalJvmOptions,
  getParticipantKmsHelmResources,
  installSpliceHelmChart,
  loadYamlFromFile,
  SPLICE_ROOT,
  SpliceCustomResourceOptions,
  spliceConfig,
  getLedgerApiAudience,
  DecentralizedSynchronizerUpgradeConfig,
  getParticipantAdminApiAudience,
  getClientId,
} from '@canton-network/splice-pulumi-common';
import { ValidatorNodeConfig } from '@canton-network/splice-pulumi-common-validator';
import { CnChartVersion } from '@canton-network/splice-pulumi-common/src/artifacts';
import { Output } from '@pulumi/pulumi';

export function checkValidatorKeyDownloadForPeriodicBackups(
  nodeName: string,
  validatorConfig: ValidatorNodeConfig,
  periodicBackupConfig?: BucketConfig
): void {
  checkKeyDownloadForPeriodicBackups(nodeName, {
    usesKms: validatorConfig.kms !== undefined,
    enableKeyDownload: validatorConfig.participant.enableKeyDownload,
    periodicBackups: periodicBackupConfig !== undefined,
  });
}

export async function installParticipant(
  validatorConfig: ValidatorNodeConfig,
  xns: ExactNamespace,
  auth0Config: Auth0Config,
  disableAuth?: boolean,
  version: CnChartVersion = activeVersion,
  defaultPostgres?: postgres.Postgres,
  extraAdminApiUsers: Array<string> = [],
  customOptions?: SpliceCustomResourceOptions
): Promise<{ participantAddress: Output<string> }> {
  const kmsConfig = validatorConfig.kms;
  const { kmsValues, kmsDependencies } = kmsConfig
    ? getParticipantKmsHelmResources(xns, kmsConfig)
    : { kmsValues: {}, kmsDependencies: [] };

  const participantPostgres =
    defaultPostgres ||
    (await postgres.installPostgres(
      xns,
      `participant-pg`,
      `participant-pg`,
      activeVersion,
      spliceConfig.pulumiProjectConfig.cloudSql,
      spliceConfig.pulumiProjectConfig.defaultSplicePostgresConfig,
      true
    ));
  const participantValues: ChartValues = {
    ...loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`,
      {
        OIDC_AUTHORITY_URL: auth0Config.auth0Domain,
        OIDC_AUTHORITY_LEDGER_API_AUDIENCE: getLedgerApiAudience(auth0Config, xns.logicalName),
        OIDC_AUTHORITY_ADMIN_API_AUDIENCE: getParticipantAdminApiAudience(
          auth0Config,
          xns.logicalName
        ),
      }
    ),
    ...loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/standalone-participant-values.yaml`
    ),
    ...kmsValues,
    metrics: {
      enable: true,
    },
    enableKeyDownload: validatorConfig.participant.enableKeyDownload,
    enableRepairCommands: validatorConfig.participant.enableRepairCommands,
  };

  const name = 'participant';
  const pgName = `participant_${DecentralizedSynchronizerUpgradeConfig.frozenMigrationId}`;
  const release = installSpliceHelmChart(
    xns,
    name,
    'splice-participant',
    {
      ...participantValues,
      logLevel: validatorConfig.logging?.level,
      apiRequestLogLevel:
        validatorConfig.logging?.cantonApiRequestLogLevel ??
        validatorConfig.logging?.apiRequestLogLevel,
      logAsyncFlush: validatorConfig.logging?.async,
      persistence: {
        databaseName: pgName,
        schema: 'participant',
        host: participantPostgres.address,
        secretName: participantPostgres.secretName,
        postgresName: participantPostgres.instanceName,
      },
      participantAdminUserNameFrom: auth0UserNameEnvVarSource('validator'),
      metrics: {
        enable: true,
      },
      additionalJvmOptions: getAdditionalJvmOptions(
        validatorConfig.participant?.additionalJvmOptions
      ),
      additionalEnvVars: (participantValues.additionalEnvVars ?? []).concat(
        validatorConfig.participant?.additionalEnvVars ?? []
      ),
      enablePostgresMetrics: true,
      resources: validatorConfig.participant.resources,
      disableAuth: disableAuth || false,
      adminApi: {
        ...participantValues.adminApi,
        disableAuth: validatorConfig.participant?.disableParticipantAdminApiAuth ?? false,
        auth: {
          ...participantValues.adminApi?.auth,
          user: `${getClientId(auth0Config, 'validator', xns.logicalName)}@clients`,
          additionalUsers: extraAdminApiUsers,
        },
      },
    },
    version,
    {
      ...(customOptions || {}),
      dependsOn: (customOptions?.dependsOn || [])
        .concat([participantPostgres])
        .concat(kmsDependencies),
      deleteBeforeReplace: true,
      aliases: [
        {
          name: `${xns.logicalName}-participant-${DecentralizedSynchronizerUpgradeConfig.frozenMigrationId}`,
        },
      ],
    }
  );
  return {
    participantAddress: release.name,
  };
}

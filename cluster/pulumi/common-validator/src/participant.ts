// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as postgres from '@canton-network/splice-pulumi-common/src/postgres';
import {
  activeVersion,
  Auth0Config,
  auth0UserNameEnvVarSource,
  ChartValues,
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
} from '@canton-network/splice-pulumi-common';
import { ValidatorNodeConfig } from '@canton-network/splice-pulumi-common-validator';
import { CnChartVersion } from '@canton-network/splice-pulumi-common/src/artifacts';
import { Output } from '@pulumi/pulumi';

export async function installParticipant(
  validatorConfig: ValidatorNodeConfig,
  xns: ExactNamespace,
  auth0Config: Auth0Config,
  disableAuth?: boolean,
  version: CnChartVersion = activeVersion,
  defaultPostgres?: postgres.Postgres,
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
      true
    ));
  const participantValues: ChartValues = {
    ...loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`,
      {
        OIDC_AUTHORITY_URL: auth0Config.auth0Domain,
      }
    ),
    ...loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/standalone-participant-values.yaml`
    ),
    ...kmsValues,
    metrics: {
      enable: true,
    },
  };

  const participantValuesWithSpecifiedAud: ChartValues = {
    ...participantValues,
    auth: {
      ...participantValues.auth,
      targetAudience: getLedgerApiAudience(auth0Config, xns.logicalName),
    },
  };

  const name = 'participant';
  const pgName = `participant_${DecentralizedSynchronizerUpgradeConfig.frozenMigrationId}`;
  const release = installSpliceHelmChart(
    xns,
    name,
    'splice-participant',
    {
      ...participantValuesWithSpecifiedAud,
      logLevel: validatorConfig.logging?.level,
      apiRequestLogLevel: validatorConfig.logging?.apiRequestLogLevel,
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
      additionalEnvVars: (participantValuesWithSpecifiedAud.additionalEnvVars ?? []).concat(
        validatorConfig.participant?.additionalEnvVars ?? []
      ),
      enablePostgresMetrics: true,
      resources: validatorConfig.participant.resources,
      disableAuth: disableAuth || false,
    },
    version,
    {
      ...(customOptions || {}),
      dependsOn: (customOptions?.dependsOn || [])
        .concat([participantPostgres])
        .concat(kmsDependencies),
    }
  );
  return {
    participantAddress: release.name,
  };
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  appsKubernetesScheduling,
  ChartValues,
  CnInput,
  daContactPoint,
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
  failOnAppVersionMismatch,
  getAdditionalJvmOptions,
  getSvAppApiAudience,
  initialPackageConfigJson,
  initialSynchronizerFeesConfig,
  InstalledHelmChart,
  installSpliceHelmChart,
  networkWideConfig,
  participantBootstrapDumpSecretName,
  persistentHeapDumpsPvc,
  sanitizedForPostgres,
  spliceInstanceNames,
  validatorOnboardingSecretName,
} from '@canton-network/splice-pulumi-common';
import {
  approvedSvIdentities,
  SynchronizerNodes,
  valuesForSvApp,
} from '@canton-network/splice-pulumi-common-sv';
import { SvConfig, svsConfig } from '@canton-network/splice-pulumi-common-sv/src/config';
import {
  delegatelessAutomationExpectedTaskDuration,
  delegatelessAutomationExpiredRewardCouponBatchSize,
  delegatelessAutomationExpiredRewardCouponNumBatches,
} from '@canton-network/splice-pulumi-common/src/automation';
import { initialAmuletPrice } from '@canton-network/splice-pulumi-common/src/initialAmuletPrice';
import { Postgres } from '@canton-network/splice-pulumi-common/src/postgres';
import { Resource } from '@pulumi/pulumi';

import { persistenceConfig } from './persistence';
import { publicScanUrl, internalScanUrl } from './scan';

export function installSvApp(
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  config: SvConfig,
  xns: ExactNamespace,
  dependsOn: CnInput<Resource>[],
  postgres: Postgres,
  synchronizerNodes: SynchronizerNodes
): InstalledHelmChart {
  const { participant } = synchronizerNodes;
  const decentralizedSynchronizer = synchronizerNodes.active;
  const allSynchronizerDependencies = [
    synchronizerNodes.active,
    synchronizerNodes.legacy,
    synchronizerNodes.upgrade,
    ...synchronizerNodes.additionalLegacy,
  ]
    .filter((n): n is NonNullable<typeof n> => n !== undefined)
    .flatMap(n => n.dependencies);
  const svDbName = `sv_${sanitizedForPostgres(config.nodeName)}`;
  const commonSvAppValues = valuesForSvApp(
    decentralizedSynchronizerMigrationConfig,
    { ...config, skipInitialization: svsConfig?.synchronizer?.skipInitialization },
    synchronizerNodes,
    config.ingressName
  );

  const svValues = {
    ...commonSvAppValues,
    ...spliceInstanceNames,
    onboardingType: config.onboarding.type,
    onboardingName: config.onboardingName,
    onboardingFoundingSvRewardWeightBps:
      config.onboarding.type == 'found-dso' ? config.onboarding.sv1SvRewardWeightBps : undefined,
    onboardingRoundZeroDuration:
      config.onboarding.type == 'found-dso' ? config.onboarding.roundZeroDuration : undefined,
    initialSynchronizerFeesConfig:
      config.onboarding.type == 'found-dso' ? initialSynchronizerFeesConfig : undefined,
    initialPackageConfigJson:
      config.onboarding.type == 'found-dso' ? initialPackageConfigJson : undefined,
    initialRound:
      config.onboarding.type == 'found-dso' ? config.onboarding.initialRound : undefined,
    initialAmuletPrice: initialAmuletPrice,
    disableOnboardingParticipantPromotionDelay: config.disableOnboardingParticipantPromotionDelay,
    decentralizedSynchronizerUrl:
      config.onboarding.type == 'found-dso'
        ? undefined
        : decentralizedSynchronizer.sv1InternalSequencerAddress,
    scan: {
      publicUrl: publicScanUrl(config),
      internalUrl: internalScanUrl(config),
    },
    expectedValidatorOnboardings: config.expectedValidatorOnboardings.map(onboarding => ({
      expiresIn: onboarding.expiresIn,
      secretFrom: {
        secretKeyRef: {
          name: validatorOnboardingSecretName(onboarding.name),
          key: 'secret',
          optional: false,
        },
      },
    })),
    isDevNet: config.isDevNet,
    approvedSvIdentities: approvedSvIdentities(),
    persistence: persistenceConfig(postgres, svDbName),
    identitiesExport: config.identitiesBackupLocation,
    participantIdentitiesDumpImport: config.bootstrappingDumpConfig
      ? { secretName: participantBootstrapDumpSecretName }
      : undefined,
    metrics: {
      enable: true,
    },
    additionalJvmOptions: getAdditionalJvmOptions(config.svApp?.additionalJvmOptions),
    failOnAppVersionMismatch: failOnAppVersionMismatch,
    participantAddress: participant.internalClusterAddress,
    onboardingPollingInterval: config.onboardingPollingInterval,
    enablePostgresMetrics: true,
    auth: {
      audience: getSvAppApiAudience(config.auth0Client.getCfg(), xns.logicalName),
      jwksUrl: `https://${config.auth0Client.getCfg().auth0Domain}/.well-known/jwks.json`,
    },
    contactPoint: daContactPoint,
    nodeIdentifier: config.onboardingName,
    delegatelessAutomationExpectedTaskDuration: delegatelessAutomationExpectedTaskDuration,
    delegatelessAutomationExpiredRewardCouponBatchSize:
      delegatelessAutomationExpiredRewardCouponBatchSize,
    delegatelessAutomationExpiredRewardCouponNumBatches:
      delegatelessAutomationExpiredRewardCouponNumBatches,
    maxVettingDelay: networkWideConfig?.maxVettingDelay,
    logLevel: config.logging?.appsLogLevel,
    apiRequestLogLevel: config.logging?.apiRequestLogLevel,
    logAsyncFlush: config.logging?.appsAsync,
    resources: config.svApp?.resources,
    periodicTopologySnapshotConfig: config.periodicTopologySnapshotConfig,
    persistentDataPvc: persistentHeapDumpsPvc(),
  } as ChartValues;

  if (config.onboarding.type == 'join-with-key') {
    svValues.joinWithKeyOnboarding = {
      sponsorApiUrl: config.onboarding.sponsorApiUrl,
      sponsorScanUrl: config.onboarding.sponsorScanUrl,
    };
  }

  return installSpliceHelmChart(
    xns,
    'sv-app',
    'splice-sv-node',
    svValues,
    config.version,
    {
      dependsOn: dependsOn.concat([postgres]).concat(allSynchronizerDependencies),
    },
    undefined,
    appsKubernetesScheduling
  );
}

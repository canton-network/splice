// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as _ from 'lodash';
import {
  DecentralizedSynchronizerUpgradeConfig,
  EnvVarConfigSchema,
  KmsConfigSchema,
  LogLevelSchema,
  K8sResourceSchema,
} from '@canton-network/splice-pulumi-common';
import { ValidatorAppConfigSchema } from '@canton-network/splice-pulumi-common-validator/src/config';
import { clusterYamlConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import { mergeConfigFragments } from '@canton-network/splice-pulumi-common/src/config/configLoader';
import { CnChartVersionSchema } from '@canton-network/splice-pulumi-common/src/config/versionSchema';
import util from 'node:util';
import { z } from 'zod';

import { TopologySnapshotSchema } from './config';
import {
  CloudSqlWithOverrideConfigSchema,
  PhysicalSynchronizersConfigOverridesSchema,
  SvMediatorConfig,
  SvMediatorConfigSchema,
  SvSequencerConfig,
  SvSequencerConfigSchema,
} from './physicalSynchronizerConfig';
import { CatchupTestSchema } from './testing';

const SvCometbftConfigSchema = z
  .object({
    nodeId: z.string().optional(),
    validatorKeyAddress: z.string().optional(),
    // defaults to {svName}-cometbft-keys if not set
    keysGcpSecret: z.string().optional(),
    enableStateSync: z.boolean().optional(),
    resources: K8sResourceSchema,
    mempool: z
      .object({
        size: z.number().optional(),
        deduplicationCacheSize: z.number().optional(),
        ttlSeconds: z.number().optional(),
      })
      .optional(),
    // Ad-hoc Helm values to be merged (nested) into the values passed to the splice-cometbft chart.
    // Values provided here override values computed by pulumi for the same nested key.
    additionalHelmValues: z.record(z.string(), z.any()).optional(),
  })
  .strict();
const CantonPruningSchema = z
  .object({
    enabled: z.boolean().optional(),
    cron: z.string().optional(),
    maxDuration: z.string().optional(),
    retentionPeriod: z.string().optional(),
  })
  .optional();
const SvParticipantConfigSchema = z
  .object({
    kms: KmsConfigSchema.optional(),
    bftSequencerConnection: z.boolean().optional(),
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
    additionalJvmOptions: z.string().optional(),
    cloudSql: CloudSqlWithOverrideConfigSchema,
    resources: K8sResourceSchema,
  })
  .strict();
const Auth0ConfigSchema = z
  .object({
    name: z.string().optional(),
    clientId: z.string().optional(),
  })
  .strict();
const AppsPgConfigSchema = z
  .object({
    cloudSql: CloudSqlWithOverrideConfigSchema,
  })
  .strict();
const SvAppConfigSchema = z
  .object({
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
    additionalJvmOptions: z.string().optional(),
    auth0: Auth0ConfigSchema.optional(),
    // defaults to {svName}-id if not set
    svIdKeyGcpSecret: z.string().optional(),
    // defaults to {svName}-cometbft-governance-key if not set
    cometBftGovernanceKeyGcpSecret: z.string().optional(),
    permissionedSynchronizer: z.boolean().optional(),
    // Map of package name -> list of versions to explicitly unvet
    additionalPackagesToUnvet: z.record(z.string(), z.array(z.string())).optional(),
    resources: K8sResourceSchema,
  })
  .strict();
const BulkStorageConfigSchema = z.object({
  enabled: z.boolean(),
});
export type BulkStorageConfig = z.infer<typeof BulkStorageConfigSchema>;
const ScanAppConfigSchema = z
  .object({
    bigQuery: z
      .object({
        dataset: z.string(),
        prefix: z.string(),
        functionsDataset: z.string().optional(),
      })
      .optional(),
    bulkStorage: BulkStorageConfigSchema.optional(),
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
    additionalJvmOptions: z.string().optional(),
    resources: K8sResourceSchema,
  })
  .strict();
const SvValidatorAppConfigSchema = z
  .object({
    walletUser: z.string().optional(),
    // TODO(#2389) inline env var into config.yaml
    sweep: z
      .object({
        fromEnv: z.string(),
      })
      .optional(),
    auth0: Auth0ConfigSchema.optional(),
    // Map of package name -> list of versions to explicitly unvet
    additionalPackagesToUnvet: z.record(z.string(), z.array(z.string())).optional(),
    resources: K8sResourceSchema,
  })
  .and(ValidatorAppConfigSchema);
// https://docs.cometbft.com/main/explanation/core/running-in-production
const CometbftLogLevelSchema = z.enum(['info', 'error', 'debug', 'none']);
// things here are declared optional even when they aren't, to allow partial overrides of defaults
// TODO(DACH-NY/canton-network-internal#4859) the above is no longer true since defaults were removed.
//   We should remove .optional() from required fields.
const SingleSvConfigSchema = z
  .object({
    publicName: z.string().optional(),
    subdomain: z.string().optional(),
    cometbft: SvCometbftConfigSchema.optional(),
    participant: SvParticipantConfigSchema.optional(),
    sequencer: SvSequencerConfigSchema.prefault({}),
    mediator: SvMediatorConfigSchema.prefault({}),
    physicalSynchronizerOverrides: PhysicalSynchronizersConfigOverridesSchema.prefault({}),
    appsPg: AppsPgConfigSchema.optional(),
    svApp: SvAppConfigSchema.optional(),
    scanApp: ScanAppConfigSchema.optional(),
    validatorApp: SvValidatorAppConfigSchema.optional(),
    pruning: z
      .object({
        cometbft: z
          .object({
            retainBlocks: z.number(),
          })
          .optional(),
        sequencer: z
          .object({
            enabled: z.boolean().optional(),
            pruningInterval: z.string().optional(),
            retentionPeriod: z.string().optional(),
          })
          .optional(),
        cantonBft: CantonPruningSchema,
        mediator: CantonPruningSchema,
        participant: CantonPruningSchema,
      })
      .optional(),
    logging: z
      .object({
        appsLogLevel: LogLevelSchema,
        appsAsync: z.boolean().default(false),
        cantonLogLevel: LogLevelSchema,
        cantonStdoutLogLevel: LogLevelSchema.optional(),
        // Log level for the Splice apps' HTTP request logging (org.lfdecentralizedtrust.splice.admin.api)
        apiRequestLogLevel: LogLevelSchema.optional(),
        // Log level for the Canton nodes' Ledger-API audit logging (com.digitalasset.canton.logging.audit)
        // Falls back to `apiRequestLogLevel` when not specified
        cantonApiRequestLogLevel: LogLevelSchema.optional(),
        cantonAsync: z.boolean().default(false),
        cometbftLogLevel: CometbftLogLevelSchema.optional(),
        cometbftExtraLogLevelFlags: z.string().optional(),
      })
      .optional(),
    periodicSnapshots: z.object({ topology: TopologySnapshotSchema.optional() }).optional(),
    testing: z.object({ catchup: CatchupTestSchema.optional() }).optional(),
    versionOverride: CnChartVersionSchema.optional(),
  })
  .strict()
  .transform(({ physicalSynchronizerOverrides, sequencer, mediator, ...svConfig }) => {
    // Implicit config merging is confusing but for now I don't really see a better way of introducting these overrides.
    // Perhaps a proper revisit of the synchronizerMigration config structure will lead to a better solution here.
    const physicalSynchronizers = DecentralizedSynchronizerUpgradeConfig.allMigrations.map(
      migration => [
        migration.id,
        {
          mediator: mergeConfigFragments(
            mediator,
            physicalSynchronizerOverrides[migration.id]?.mediator || {}
          ) as SvMediatorConfig,
          sequencer: mergeConfigFragments(
            sequencer,
            physicalSynchronizerOverrides[migration.id]?.sequencer || {}
          ) as SvSequencerConfig,
        },
      ]
    );
    return {
      ...svConfig,
      physicalSynchronizers: _.fromPairs(physicalSynchronizers),
    };
  });
const AllSvsConfigurationSchema = z.record(z.string(), SingleSvConfigSchema);
const SvsConfigurationSchema = z.object({
  svs: AllSvsConfigurationSchema,
});

type SingleSvConfig = z.infer<typeof AllSvsConfigurationSchema>;
export type SingleSvConfiguration = z.infer<typeof SingleSvConfigSchema>;

const clusterSvsConfiguration: SingleSvConfig = SvsConfigurationSchema.parse(clusterYamlConfig).svs;

export const allConfiguredSvs: string[] = Object.keys(clusterSvsConfiguration);

// SVs that don't match the standard sv-X pattern; we deploy those always, independently of DSO_SIZE
export const configuredExtraSvs: string[] = allConfiguredSvs.filter(k => !k.match(/^sv(-\d+)?$/));

export const configForSv = (svName: string): SingleSvConfiguration => {
  return clusterSvsConfiguration[svName];
};

export const allSvsConfiguration: SingleSvConfiguration[] = allConfiguredSvs.map(sv => {
  const svConfig = configForSv(sv);
  console.error(
    `Loaded ${sv} config`,
    util.inspect(svConfig, {
      depth: null,
      maxStringLength: null,
    })
  );
  return svConfig;
});

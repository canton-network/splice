// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
  auth0UserNameEnvVarSource,
  DecentralizedSynchronizerMigrationConfig,
  DomainMigrationIndex,
  ExactNamespace,
  installLedgerApiUserSecret,
  SpliceCustomResourceOptions,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  InstalledMigrationSpecificSv,
  SingleSvConfiguration,
  StaticCometBftConfigWithNodeName,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { installPostgres, Postgres } from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';
import {
  InStackCantonBftDecentralizedSynchronizerNode,
  InStackCometBftDecentralizedSynchronizerNode,
} from '@lfdecentralizedtrust/splice-pulumi-sv-canton/src/decentralizedSynchronizerNode';

import { spliceConfig } from '../../common/src/config/config';

export async function installCantonComponents(
  xns: ExactNamespace,
  migrationId: DomainMigrationIndex,
  auth0Client: Auth0Client,
  svConfig: {
    onboardingName: string;
    ingressName: string;
    auth0SvAppName: string;
    isFirstSv: boolean;
    isCoreSv: boolean;
  } & SingleSvConfiguration,
  migrationConfig: DecentralizedSynchronizerMigrationConfig,
  cometbft: {
    nodeConfigs: {
      self: StaticCometBftConfigWithNodeName;
      sv1: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    };
    enableStateSync?: boolean;
    enableTimeoutCommit?: boolean;
  },
  dbs?: {
    participant: Postgres;
    mediator: Postgres;
    sequencer: Postgres;
  },
  opts?: SpliceCustomResourceOptions,
  disableProtection?: boolean,
  imagePullServiceAccountName?: string
): Promise<InstalledMigrationSpecificSv | undefined> {
  const isActiveMigration = migrationConfig.active.id === migrationId;

  const auth0Config = auth0Client.getCfg();
  const ledgerApiUserSecret = installLedgerApiUserSecret(
    auth0Client,
    xns,
    `sv-canton-migration-${migrationId}`,
    'sv'
  );
  const ledgerApiUserSecretSource = auth0UserNameEnvVarSource(
    `sv-canton-migration-${migrationId}`,
    true
  );

  const migrationStillRunning = migrationConfig.isStillRunning(migrationId);
  const migrationInfo = migrationConfig.allMigrations.find(
    migration => migration.id === migrationId
  );
  if (!migrationInfo) {
    throw new Error(`Migration ${migrationId} not found in migration config`);
  }
  const version = isActiveMigration
    ? (svConfig.versionOverride ?? migrationInfo.version)
    : migrationInfo.version;
  const mediatorPostgres =
    dbs?.mediator ||
    (await installPostgres(
      xns,
      `mediator-${migrationId}-pg`,
      `mediator-pg`,
      version,
      svConfig.mediator?.cloudSql || spliceConfig.pulumiProjectConfig.cloudSql,
      true,
      {
        isActive: migrationStillRunning,
        migrationId,
        disableProtection,
      }
    ));
  const sequencerPostgres =
    dbs?.sequencer ||
    (await installPostgres(
      xns,
      `sequencer-${migrationId}-pg`,
      `sequencer-pg`,
      version,
      svConfig.sequencer?.cloudSql || spliceConfig.pulumiProjectConfig.cloudSql,
      true,
      { isActive: migrationStillRunning, migrationId, disableProtection }
    ));
  if (migrationStillRunning) {
    const decentralizedSynchronizerNode = migrationInfo.sequencer.enableBftSequencer
      ? new InStackCantonBftDecentralizedSynchronizerNode(
          svConfig,
          migrationId,
          svConfig.ingressName,
          xns,
          {
            sequencerPostgres: sequencerPostgres,
            mediatorPostgres: mediatorPostgres,
            setCoreDbNames: svConfig.isCoreSv,
          },
          version,
          imagePullServiceAccountName,
          opts
        )
      : new InStackCometBftDecentralizedSynchronizerNode(
          svConfig,
          cometbft,
          migrationId,
          xns,
          {
            sequencerPostgres: sequencerPostgres,
            mediatorPostgres: mediatorPostgres,
            setCoreDbNames: svConfig.isCoreSv,
          },
          isActiveMigration,
          migrationConfig.isRunningMigration(),
          svConfig.onboardingName,
          version,
          imagePullServiceAccountName,
          disableProtection,
          migrationInfo.cometbft?.volumeSize,
          opts
        );
    return {
      decentralizedSynchronizer: decentralizedSynchronizerNode,
    };
  } else {
    return undefined;
  }
}

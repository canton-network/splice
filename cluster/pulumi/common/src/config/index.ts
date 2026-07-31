// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { spliceEnvConfig } from './envConfig';

export * from './configSchema';
export * from './kms';
export * from './cloudSql';
export { spliceEnvConfig as config } from './envConfig';

export const DeploySvRunbook = spliceEnvConfig.envFlag('SPLICE_DEPLOY_SV_RUNBOOK', false);
export const DeployValidatorRunbook = spliceEnvConfig.envFlag(
  'SPLICE_DEPLOY_VALIDATOR_RUNBOOK',
  false
);
export const clusterProdLike = spliceEnvConfig.envFlag('GCP_CLUSTER_PROD_LIKE');


// Datastream pipeline inclusion flags
export const enableLegacyDatastream = spliceEnvConfig.envFlag('ENABLE_LEGACY_DATASTREAM', true);
export const enableStagProdDatastream = spliceEnvConfig.envFlag('ENABLE_STAG_PROD_DATASTREAM', true); 

// Datastream operational states ('RUNNING' or 'PAUSED')
export const legacyDatastreamDesiredState = (
  spliceEnvConfig.optionalEnv('LEGACY_DATASTREAM_DESIRED_STATE') ?? 'RUNNING'
) as 'RUNNING' | 'PAUSED';

export const stagProdDatastreamDesiredState = (
  spliceEnvConfig.optionalEnv('STAG_PROD_DATASTREAM_DESIRED_STATE') ?? 'RUNNING'
) as 'RUNNING' | 'PAUSED';

// Table expiration in milliseconds for BigQuery prod dataset (defaults to 3 days: 259,200,000 ms)
const THREE_DAYS_MS = 3 * 24 * 60 * 60 * 1000;
const rawExpiration = spliceEnvConfig.optionalEnv('PROD_TABLE_EXPIRATION_MS');
export const prodTableExpirationMs = rawExpiration ? Number(rawExpiration) : THREE_DAYS_MS;

// During development we often overwrite the same tag so we use imagePullPolicy: Always.
// Outside of development, we use the default which corresponds to IfNotPresent
// (unless the tag is LATEST which it never is in our setup).
export const imagePullPolicy = clusterProdLike ? {} : { imagePullPolicy: 'Always' };

export const supportsSvRunbookReset = spliceEnvConfig.envFlag('SUPPORTS_SV_RUNBOOK_RESET', false);

export const isMainNet = spliceEnvConfig.envFlag('IS_MAINNET', false);
export const isDevNet = spliceEnvConfig.envFlag('IS_DEVNET', true) && !isMainNet;
export const clusterSmallDisk = spliceEnvConfig.envFlag('CLUSTER_SMALL_DISK', false);
export const failOnAppVersionMismatch: boolean = spliceEnvConfig.envFlag(
  'FAIL_ON_APP_VERSION_MISMATCH',
  true
);

export const LogLevelSchema = z.enum(['DEBUG', 'INFO', 'WARN', 'ERROR']);
export type LogLevel = z.infer<typeof LogLevelSchema>;

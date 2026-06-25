// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  CloudSqlConfig,
  clusterSmallDisk,
  ExactNamespace,
  loadYamlFromFile,
  SPLICE_ROOT,
  supportsSvRunbookReset,
} from '@canton-network/splice-pulumi-common';
import { spliceConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import { BitnamiPostgres, CloudPostgres } from '@canton-network/splice-pulumi-common/src/postgres';

export async function installPostgres(
  xns: ExactNamespace,
  name: string,
  secretName: string,
  selfHostedValuesFile: string,
  isActive: boolean = true,
  cloudSqlConfigOverride?: CloudSqlConfig
): Promise<BitnamiPostgres | CloudPostgres> {
  const cloudSqlConfig = cloudSqlConfigOverride ?? spliceConfig.pulumiProjectConfig.cloudSql;
  if (cloudSqlConfig.enabled) {
    return CloudPostgres.install(`${xns.logicalName}-${name}`, {
      active: isActive,
      alias: name,
      cloudSqlConfig,
      disableProtection: supportsSvRunbookReset,
      instanceName: name,
      namespace: xns,
      secretName,
    });
  } else {
    const valuesFromFile = loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/${selfHostedValuesFile}`
    );
    const volumeSize = determineVolumeSize(valuesFromFile.primary?.persistence?.size);
    return new BitnamiPostgres(xns, name, {
      secretName,
      volumeSize,
      disableProtection: supportsSvRunbookReset,
    });
  }
}

function determineVolumeSize(volumeSizeFromFile: string | undefined): string | undefined {
  const gigs = (s: string) => parseInt(s.replace('Gi', ''));
  return clusterSmallDisk && volumeSizeFromFile && gigs(volumeSizeFromFile) > 240
    ? '240Gi'
    : volumeSizeFromFile;
}

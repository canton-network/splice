// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  exactNamespace,
} from '@canton-network/splice-pulumi-common';
import {
  configForSv,
  coreSvsToDeploy,
  StaticSvConfig,
} from '@canton-network/splice-pulumi-common-sv';
import {
  InstalledSv,
  installSvNodeStandalone,
} from '@canton-network/splice-pulumi-common-sv/src/sv';

interface DsoArgs {
  auth0Client: Auth0Client;
  decentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerMigrationConfig;
}

export class Dso extends pulumi.ComponentResource {
  args: DsoArgs;
  sv1: Promise<InstalledSv>;
  allSvs: Promise<InstalledSv[]>;

  private async installSvNode(
    svConf: StaticSvConfig,
    extraDependsOn: CnInput<pulumi.Resource>[] = []
  ): Promise<InstalledSv> {
    const xns = exactNamespace(svConf.nodeName, true);
    const dynamicConfig = configForSv(svConf.nodeName);
    return await installSvNodeStandalone(
      xns,
      svConf,
      dynamicConfig,
      this.args.auth0Client,
      extraDependsOn
    );
  }

  private async installDso() {
    const relevantSvConfs = coreSvsToDeploy;
    const [sv1Conf, ...restSvConfs] = relevantSvConfs;

    const sv1 = await this.installSvNode(sv1Conf);

    // TODO(#893): long-term CantonBFT deployments should be robust enough to onboard in parallel again?
    const incrementalOnboarding =
      this.args.decentralizedSynchronizerUpgradeConfig.active.sequencer.enableBftSequencer;

    // recursive install function to allow injecting dependencies on previous svs
    const installSvNodes = async (
      configs: StaticSvConfig[],
      previousSvs: InstalledSv[] = []
    ): Promise<InstalledSv[]> => {
      if (configs.length === 0) {
        return previousSvs;
      }
      const [conf, ...remainingConfigs] = configs;

      const newSv = await this.installSvNode(
        conf,
        incrementalOnboarding ? previousSvs.map(sv => sv.svApp) : []
      );
      return installSvNodes(remainingConfigs, [...previousSvs, newSv]);
    };
    const restSvs = await installSvNodes(restSvConfs);

    return { sv1, allSvs: [sv1, ...restSvs] };
  }

  constructor(name: string, args: DsoArgs, opts?: pulumi.ComponentResourceOptions) {
    super('canton:network:dso', name, args, opts);
    this.args = args;

    const dso = this.installDso();

    this.sv1 = dso.then(r => r.sv1);

    this.allSvs = dso.then(r => r.allSvs);

    this.registerOutputs({});
  }
}

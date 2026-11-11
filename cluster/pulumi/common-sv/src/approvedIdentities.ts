// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  config,
  DeploySvRunbook,
  getPathToPublicConfigFile,
  loadYamlFromFile,
  SvIdKey,
  svKeyFromSecret,
} from '@canton-network/splice-pulumi-common';
import _ from 'lodash';

import { coreSvsToDeploy, svRunbookConfig } from './svConfigs';

export type ApprovedSvIdentity = {
  name: string;
  publicKey: string | pulumi.Output<string>;
  rewardWeightBps: number;
};

export type ApprovedSvIdentityFromYamlFile = {
  name: string;
  publicKey: string | pulumi.Output<string>;
  // js-yaml after 4.2 doesn't support specifying numbers with separating underscores (e.g. 100_000),
  // which means that it will parse that as a string and break the on Helm schema validation.
  // This is compliant with the yaml spec, which doesn't define numbers with underscores as possible.
  // For normal numbers (e.g 100000), it still gets parsed as a number.
  rewardWeightBps: string | number;
};

export function approvedSvIdentitiesFile(): string | undefined {
  return getPathToPublicConfigFile('approved-sv-id-values.yaml');
}

function approvedSvIdentitiesFromFile(): ApprovedSvIdentityFromYamlFile[] {
  const file = approvedSvIdentitiesFile();
  return file ? loadYamlFromFile(file).approvedSvIdentities : [];
}

function approvedSvIdentitiesFromConfig(): ApprovedSvIdentity[] {
  const approveSvRunbook = DeploySvRunbook || config.envFlag('APPROVE_SV_RUNBOOK');
  const allSvsToApprove = coreSvsToDeploy.concat(approveSvRunbook ? [svRunbookConfig] : []);

  const svIdKeys = allSvsToApprove.reduce<Record<string, pulumi.Output<SvIdKey>>>((acc, conf) => {
    const secretName = conf.svIdKeySecretName ?? conf.nodeName.replaceAll('-', '') + '-id';
    return {
      ...acc,
      [conf.onboardingName]: svKeyFromSecret(secretName),
    };
  }, {});

  return Object.entries(svIdKeys).map<ApprovedSvIdentity>(([onboardingName, keys]) => ({
    name: onboardingName,
    publicKey: keys.publicKey, // we always use that one if we have it, overriding approved-sv-id-values-$CLUSTER.yaml
    rewardWeightBps: 10000, // if already defined in approved-sv-id-values-$CLUSTER.yaml, this will be ignored.
  }));
}

export function approvedSvIdentities(): ApprovedSvIdentity[] {
  const rawFromFile = approvedSvIdentitiesFromFile();
  const fromFile: ApprovedSvIdentity[] = rawFromFile.map(identity => ({
    name: identity.name,
    rewardWeightBps: parseInt(String(identity.rewardWeightBps).replaceAll('_', ''), 10),
    publicKey: identity.publicKey,
  }));
  const fromConfig = approvedSvIdentitiesFromConfig();

  // We override public keys to the locally configured one,
  // to support using real approved-sv-id-values files on CI clusters that don't have access to the real keys.
  // TODO(DACH-NY/canton-network-internal#2358) Consider not doing this on dev/test/main.
  const configuredPublicKeys = fromConfig.reduce(
    (acc, identity) => ({ ...acc, [identity.name]: identity.publicKey }),
    {} as Record<string, string | pulumi.Output<string>>
  );

  return _.uniqBy([...fromFile, ...fromConfig], 'name').map(identity => ({
    name: identity.name,
    rewardWeightBps: identity.rewardWeightBps,
    publicKey: configuredPublicKeys[identity.name] ?? identity.publicKey,
  }));
}

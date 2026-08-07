// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  DecentralizedSynchronizerUpgradeConfig,
  getDnsNames,
} from '@canton-network/splice-pulumi-common';
import { allSvsToDeployBasic } from '@canton-network/splice-pulumi-common-sv/src/svConfigsBasic';

import { loadIPRanges } from './ipRanges';
import { CLUSTER_INGRESS_NS, createIstioIpAllowPolicies, istioIngressSelector } from './policies';

export function configureSequencerWhitelist(): pulumi.Output<pulumi.Resource[]>[] {
  const dnsNames = [getDnsNames().cantonDnsName, getDnsNames().daDnsName];
  const migrations = DecentralizedSynchronizerUpgradeConfig.runningMigrations();

  const publicApiHosts = allSvsToDeployBasic.flatMap(sv =>
    migrations.flatMap(migration =>
      dnsNames.map(dns => `sequencer-${migration.id}.${sv.ingressName}.${dns}`)
    )
  );
  const p2pHosts = allSvsToDeployBasic.flatMap(sv =>
    migrations
      .filter(migration => migration.sequencer.enableBftSequencer)
      .flatMap(migration =>
        dnsNames.map(dns => `sequencer-p2p-${migration.id}.${sv.ingressName}.${dns}`)
      )
  );

  const policies = [
    createIstioIpAllowPolicies({
      namePrefix: 'sequencer-pub-ip-whitelist',
      namespace: CLUSTER_INGRESS_NS,
      selector: istioIngressSelector,
      ipRanges: loadIPRanges(),
      to: [{ operation: { hosts: publicApiHosts } }],
    }),
  ];
  if (p2pHosts.length > 0) {
    policies.push(
      createIstioIpAllowPolicies({
        namePrefix: 'sequencer-p2p-ip-whitelist',
        namespace: CLUSTER_INGRESS_NS,
        selector: istioIngressSelector,
        ipRanges: loadIPRanges(true),
        to: [{ operation: { hosts: p2pHosts } }],
      })
    );
  }
  return policies;
}

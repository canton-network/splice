// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { getDnsNames } from '@canton-network/splice-pulumi-common';
import { allSvsToDeployBasic } from '@canton-network/splice-pulumi-common-sv/src/svConfigsBasic';

import { loadIPRanges } from './ipRanges';
import { CLUSTER_INGRESS_NS, createIstioIpAllowPolicies, istioIngressSelector } from './policies';

export function configureScanAndSvAppWhitelist(): pulumi.Output<pulumi.Resource[]> {
  const dnsNames = [getDnsNames().cantonDnsName, getDnsNames().daDnsName];
  const hosts = allSvsToDeployBasic.flatMap(sv =>
    dnsNames.flatMap(dns => [`scan.${sv.ingressName}.${dns}`, `sv.${sv.ingressName}.${dns}`])
  );
  return createIstioIpAllowPolicies({
    namePrefix: 'scan-sv-app-ip-whitelist',
    namespace: CLUSTER_INGRESS_NS,
    selector: istioIngressSelector,
    ipRanges: loadIPRanges(),
    to: [{ operation: { hosts } }],
  });
}

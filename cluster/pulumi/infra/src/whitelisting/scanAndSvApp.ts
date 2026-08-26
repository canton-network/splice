// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { getDnsNames } from '@canton-network/splice-pulumi-common';
import { allSvsForIngressWhitelistingBasic } from '@canton-network/splice-pulumi-common-sv/src/svConfigsBasic';

import { loadIPRanges } from './ipRanges';
import { createIstioIpAllowPolicies, istioIngressSelector } from './policies';

export function configureScanAndSvAppWhitelist(
  namespace: k8s.core.v1.Namespace
): pulumi.Output<pulumi.Resource[]> {
  const dnsNames = [getDnsNames().cantonDnsName, getDnsNames().daDnsName];
  const hosts = allSvsForIngressWhitelistingBasic.flatMap(sv =>
    dnsNames.flatMap(dns => [`scan.${sv.ingressName}.${dns}`, `sv.${sv.ingressName}.${dns}`])
  );
  return createIstioIpAllowPolicies({
    namePrefix: 'scan-sv-app-ip-whitelist',
    namespace: namespace.metadata.name,
    selector: istioIngressSelector,
    ipRanges: loadIPRanges(),
    to: [{ operation: { hosts } }],
  });
}

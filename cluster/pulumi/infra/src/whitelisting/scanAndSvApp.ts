// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { getDnsNames, SPLICE_ROOT } from '@canton-network/splice-pulumi-common';
import { allSvsToDeployBasic } from '@canton-network/splice-pulumi-common-sv/src/svConfigsBasic';

import { loadIPRanges } from './ipRanges';
import { createIstioIpAllowPolicies, istioIngressSelector } from './policies';
import { readSvPublicIngressPathsByAudience } from './svPublicEndpoints';

export const svOpenApiFile = `${SPLICE_ROOT}/apps/sv/src/main/openapi/sv-internal.yaml`;

function hostsFor(prefix: string): string[] {
  const dnsNames = [getDnsNames().cantonDnsName, getDnsNames().daDnsName];
  return allSvsToDeployBasic.flatMap(sv =>
    dnsNames.map(dns => `${prefix}.${sv.ingressName}.${dns}`)
  );
}

export function configureScanAndSvAppWhitelist(
  namespace: k8s.core.v1.Namespace
): pulumi.Output<pulumi.Resource[]>[] {
  const scanHosts = hostsFor('scan');
  const svHosts = hostsFor('sv');
  const publicPaths = readSvPublicIngressPathsByAudience(svOpenApiFile);

  return [
    createIstioIpAllowPolicies({
      namePrefix: 'scan-app-ip-whitelist',
      namespace: namespace.metadata.name,
      selector: istioIngressSelector,
      ipRanges: loadIPRanges(),
      to: [{ operation: { hosts: scanHosts } }],
    }),
    createIstioIpAllowPolicies({
      namePrefix: 'sv-app-validators-ip-whitelist',
      namespace: namespace.metadata.name,
      selector: istioIngressSelector,
      ipRanges: loadIPRanges(),
      to: [{ operation: { hosts: svHosts, paths: publicPaths['validators'] } }],
    }),
    createIstioIpAllowPolicies({
      namePrefix: 'sv-app-svs-ip-whitelist',
      namespace: namespace.metadata.name,
      selector: istioIngressSelector,
      ipRanges: loadIPRanges(true),
      to: [{ operation: { hosts: svHosts, paths: publicPaths['svs'] } }],
    }),
  ];
}

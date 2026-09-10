// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';

import { createIstioIpAllowPolicies, istioApiVersion } from './policies';

export function configureGatewayAccessPolicies(
  ingressNs: k8s.core.v1.Namespace,
  ipRanges: pulumi.Output<string[]>,
  suffix: string
): pulumi.Output<pulumi.Resource[]> {
  const selector = {
    matchLabels: {
      app: `istio-ingress${suffix}`,
    },
  };
  const defaultDenyAll = new k8s.apiextensions.CustomResource(
    `istio-access-policy-deny-all${suffix}`,
    {
      apiVersion: istioApiVersion,
      kind: 'AuthorizationPolicy',
      metadata: {
        name: `istio-access-policy-deny-all${suffix}`,
        namespace: ingressNs.metadata.name,
      },
      // empty spec is deny all
      spec: { selector },
    }
  );
  return createIstioIpAllowPolicies({
    namePrefix: `istio-access-policy-allow${suffix}`,
    namespace: ingressNs.metadata.name,
    selector,
    ipRanges,
  }).apply(policies => [defaultDenyAll, ...policies]);
}

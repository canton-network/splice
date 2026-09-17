// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';

import { istioApiVersion } from './policies';

export function configurePublicTokenRegistry(
  ingressNs: k8s.core.v1.Namespace
): k8s.apiextensions.CustomResource[] {
  return [
    new k8s.apiextensions.CustomResource('allow-public-token-registry', {
      apiVersion: istioApiVersion,
      kind: 'AuthorizationPolicy',
      metadata: {
        name: 'allow-public-token-registry',
        namespace: ingressNs.metadata.name,
      },
      spec: {
        selector: {
          matchLabels: {
            app: 'istio-ingress',
          },
        },
        action: 'ALLOW',
        rules: [
          {
            to: [
              {
                operation: {
                  paths: ['/registry/*'],
                },
              },
            ],
          },
        ],
      },
    }),
  ];
}

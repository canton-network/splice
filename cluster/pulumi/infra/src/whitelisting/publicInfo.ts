// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import { getDnsNames } from '@canton-network/splice-pulumi-common';
import { allSvsToDeployBasic } from '@canton-network/splice-pulumi-common-sv/src/svConfigsBasic';
import { spliceConfig } from '@canton-network/splice-pulumi-common/src/config/config';

import { istioApiVersion } from './policies';

export function configurePublicInfo(
  ingressNs: k8s.core.v1.Namespace
): k8s.apiextensions.CustomResource[] {
  return spliceConfig.pulumiProjectConfig.hasPublicInfo
    ? [
        new k8s.apiextensions.CustomResource('allow-sv-info', {
          apiVersion: istioApiVersion,
          kind: 'AuthorizationPolicy',
          metadata: {
            name: 'allow-sv-info',
            namespace: ingressNs.metadata.name,
          },
          spec: {
            selector: {
              matchLabels: {
                istio: 'ingress',
              },
            },
            action: 'ALLOW',
            rules: [
              {
                to: [
                  {
                    operation: {
                      hosts: [
                        // We could also have done `info.sv*.whatever` here but enumerating what we expect seems slightly more secure
                        ...new Set(
                          allSvsToDeployBasic
                            .map(sv => [
                              `info.${sv.ingressName}.${getDnsNames().cantonDnsName}`,
                              `info.${sv.ingressName}.${getDnsNames().daDnsName}`,
                            ])
                            .flat()
                        ),
                      ],
                    },
                  },
                ],
              },
            ],
          },
        }),
      ]
    : [];
}

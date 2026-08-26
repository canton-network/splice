// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import {
  HELM_MAX_HISTORY_SIZE,
  exactNamespace,
  infraAffinityAndTolerations,
} from '@canton-network/splice-pulumi-common';

export function configureSweet(): k8s.helm.v3.Release {
  const operatorNs = exactNamespace('sweet-operator', false, true);
  const sweetNs = exactNamespace('sweet', false, true);

  const apiKey = gcp.secretmanager.getSecretVersionOutput({
    secret: 'sweet-api-key',
  }).secretData;
  const secret = gcp.secretmanager.getSecretVersionOutput({
    secret: 'sweet-secret',
  }).secretData;

  return new k8s.helm.v3.Release(
    'sweet-operator',
    {
      name: 'sweet-operator',
      chart: 'oci://registry.sweet.security/helm/operatorchart',
      version: '1.0.265090+06a1b12d61fc35ceb20b350388f7e812d382e4b2',
      namespace: sweetNs.ns.metadata.name,
      values: {
        sweet: {
          apiKey,
          secret,
        },
        operator: {
          ...infraAffinityAndTolerations,
        },
        frontier: {
          extraValues: {
            informer: {
              ...infraAffinityAndTolerations,
            },
          }
        },
        admiral: {
          extraValues: {
            admirald: {
              ...infraAffinityAndTolerations,
            },
          },
        }
      },
      maxHistory: HELM_MAX_HISTORY_SIZE,
    },
    {
      dependsOn: [operatorNs.ns, sweetNs.ns],
    }

  );

}

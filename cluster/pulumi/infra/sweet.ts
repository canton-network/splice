// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import {
  HELM_MAX_HISTORY_SIZE,
  exactNamespace,
  infraAffinityAndTolerations,
} from '@canton-network/splice-pulumi-common';

//helm install --namespace sweet --create-namespace sweet-operator
// oci://registry.sweet.security/helm/operatorchart --set sweet.apiKey=[...] --set sweet.secret=[...]

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
    'sweet',
    {
      name: 'sweet',
      chart: 'operatorchart',
      version: '1.0.265090',
      namespace: sweetNs.ns.metadata.name,
      repositoryOpts: {
        repo: 'oci://registry.sweet.security/helm',
      },
      values: {
        apiKey,
        secret,
        operator: {
          ...infraAffinityAndTolerations,
        }
      },
      maxHistory: HELM_MAX_HISTORY_SIZE,
    },
    {
      dependsOn: [operatorNs.ns, sweetNs.ns],
    }

  );

}

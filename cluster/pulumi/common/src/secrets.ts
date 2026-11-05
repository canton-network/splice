// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { DockerConfig } from '@canton-network/splice-pulumi-common/src/dockerConfig';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';
import { Output } from '@pulumi/pulumi';

import { CnInput } from './helm';
import { btoa, ExactNamespace } from './utils';

export type SvIdKey = {
  publicKey: string;
  privateKey: string;
};

export type GrafanaKeys = {
  adminUser: string;
  adminPassword: string;
};

export function svKeyFromSecret(secretName: string): pulumi.Output<SvIdKey> {
  const keyJson = getSecretVersionOutput({ secret: secretName });
  return keyJson.apply(k => {
    const secretData = k.secretData;
    const parsed = JSON.parse(secretData);
    return {
      publicKey: String(parsed.publicKey),
      privateKey: String(parsed.privateKey),
    };
  });
}

export function imagePullSecretByNamespaceName(
  ns: string,
  dependsOn: pulumi.Resource[] = []
): pulumi.Resource[] {
  return imagePullSecretByNamespaceNameForServiceAccount(ns, 'default', dependsOn);
}

export function imagePullSecretByNamespaceNameForServiceAccount(
  ns: string,
  serviceAccountName: string,
  dependsOn: pulumi.Resource[] = []
): pulumi.Resource[] {
  // eslint-disable-next-line no-process-env
  const kubecfg = process.env['KUBECONFIG'];
  // k8sProvider saves the absolute path to kubeconfig if it's defined in KUBECONFIG env var, which makes
  // it not portable between machines, so we temporarily remove this env var to avoid that.
  // eslint-disable-next-line no-process-env
  kubecfg && delete process.env.KUBECONFIG;
  const k8sProvider = new k8s.Provider(`k8s-imgpull-${ns}-${serviceAccountName}`, {
    enableServerSideApply: true,
  });
  // eslint-disable-next-line no-process-env
  kubecfg && (process.env['KUBECONFIG'] = kubecfg);

  // We do this to avoid having to rename existing secrets
  const secretName =
    serviceAccountName === 'default' ? 'docker-reg-cred' : `${serviceAccountName}-docker-reg-cred`;
  const secret = DockerConfig.getConfig().createImagePullSecret(ns, secretName, dependsOn);
  return [
    secret,
    patchServiceAccountWithImagePullSecret(
      ns,
      serviceAccountName,
      secret.metadata.name,
      k8sProvider
    ),
  ];
}

function patchServiceAccountWithImagePullSecret(
  ns: string,
  serviceAccountName: string,
  secretName: Output<string>,
  k8sProvider: k8s.Provider
): pulumi.Resource {
  const patch = new k8s.core.v1.ServiceAccountPatch(
    ns + '-' + serviceAccountName,
    {
      imagePullSecrets: [
        {
          name: secretName,
        },
      ],
      metadata: {
        name: serviceAccountName,
        namespace: ns,
      },
    },
    {
      provider: k8sProvider,
    }
  );

  return patch;
}

export function imagePullSecret(ns: ExactNamespace): CnInput<pulumi.Resource>[] {
  return imagePullSecretByNamespaceName(ns.logicalName, [ns.ns]);
}

export function imagePullSecretWithNonDefaultServiceAccount(
  ns: ExactNamespace,
  serviceAccountName: string
): CnInput<pulumi.Resource>[] {
  const serviceAccount = new k8s.core.v1.ServiceAccount(
    serviceAccountName,
    {
      metadata: {
        name: serviceAccountName,
        namespace: ns.logicalName,
      },
    },
    {
      dependsOn: ns.ns,
    }
  );
  return imagePullSecretByNamespaceNameForServiceAccount(ns.logicalName, serviceAccountName, [
    serviceAccount,
  ]);
}

export function svKeySecret(ns: ExactNamespace, keys: CnInput<SvIdKey>): k8s.core.v1.Secret {
  const logicalPulumiName = 'cn-app-sv-key';
  const secretName = 'splice-app-sv-key';
  const data = pulumi.output(keys).apply(ks => {
    return {
      public: btoa(ks.publicKey),
      private: btoa(ks.privateKey),
    };
  });
  return new k8s.core.v1.Secret(
    logicalPulumiName,
    {
      metadata: {
        name: secretName,
        namespace: ns.logicalName,
      },
      type: 'Opaque',
      data: data,
    },
    {
      dependsOn: [ns.ns],
    }
  );
}

export function installPostgresPasswordSecret(
  ns: ExactNamespace,
  password: pulumi.Input<string>,
  secretName: string,
  existingSecretName?: string,
  retainDbResourcesOnDelete: boolean = false
): k8s.core.v1.Secret {
  const importOpts =
    existingSecretName !== undefined
      ? {
          import: `${ns.logicalName}/${existingSecretName}`,
          ignoreChanges: ['metadata.name', 'data.postgresPassword'],
        }
      : {};
  return new k8s.core.v1.Secret(
    `cn-app-${ns.logicalName}-${secretName}`,
    {
      metadata: {
        name: secretName,
        namespace: ns.logicalName,
      },
      type: 'Opaque',
      data: {
        postgresPassword: pulumi.output(password).apply(p => btoa(p || '')), // password is undefined in dump-config
      },
    },
    {
      dependsOn: [ns.ns],
      retainOnDelete: retainDbResourcesOnDelete,
      ...importOpts,
    }
  );
}

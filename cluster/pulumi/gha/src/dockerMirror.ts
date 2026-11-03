// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  DockerConfig,
  infraKubernetesScheduling,
  standardStorageClassName,
} from '@canton-network/splice-pulumi-common';
import { Namespace } from '@pulumi/kubernetes/core/v1';

export function installDockerRegistryMirror(): k8s.helm.v3.Release {
  const namespace = new Namespace('docker-mirror', {
    metadata: {
      name: 'docker-mirror',
    },
  });

  // Expected GCP secret format: {"username": "<dockerhub-user>", "password": "<PAT>"}
  const dockerHubCredentials = DockerConfig.fetchCredentialsFromSecret('docker-mirror-credentials');

  return new k8s.helm.v3.Release(
    'docker-registry-mirror',
    {
      name: 'docker-registry-mirror',
      chart: 'docker-registry',
      version: '3.0.0',
      namespace: namespace.metadata.name,
      repositoryOpts: {
        repo: 'https://twuni.github.io/docker-registry.helm',
      },
      values: {
        extraEnvVars: [
          {
            // Avoid `traces export...connection refused` error spam
            name: 'OTEL_TRACES_EXPORTER',
            value: 'none',
          },
          {
            // Keep cached images for 30 days before expiring them (default: 168h = 7 days).
            name: 'REGISTRY_PROXY_TTL',
            value: '720h',
          },
        ],
        proxy: {
          // Configure the registry to act as a read-through cache for the Docker Hub.
          enabled: true,
          // Docker Hub credentials used by the mirror when pulling from upstream,
          // so that we get authenticated (and thus higher) rate limits.
          username: dockerHubCredentials.username,
          password: dockerHubCredentials.password,
        },
        persistence: {
          storageClass: standardStorageClassName,
          enabled: true,
          size: '20Gi',
        },
        configData: {
          storage: {
            // Enable blob/manifest deletion so the proxy's built-in TTL-based
            // scheduler can remove expired cached content.
            // See: https://distribution.github.io/distribution/recipes/mirror/
            delete: {
              enabled: true,
            },
            // Protection against https://github.com/distribution/distribution/issues/2966
            cache: {
              blobdescriptor: '',
            },
          },
        },
        ...infraKubernetesScheduling,
      },
    },
    {
      dependsOn: [namespace],
    }
  );
}

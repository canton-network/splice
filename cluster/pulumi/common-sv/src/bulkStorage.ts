// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { ChartValues, ExactNamespace } from '@canton-network/splice-pulumi-common';
import {
  ClusterBasename,
  GcpRegion,
} from '@canton-network/splice-pulumi-common/src/config/gcpConfig';

import { BulkStorageConfig } from './singleSvConfig';

type BulkStorageBucket = {
  bucket: gcp.storage.Bucket;
  region: string;
  secret: k8s.core.v1.Secret;
};

export type BulkStorageBuckets = {
  staging: BulkStorageBucket;
  committed: BulkStorageBucket;
};

export function installScanBulkStorage(
  xns: ExactNamespace,
  bulkStorageConfig: BulkStorageConfig,
  forceDestroy: boolean = false
): BulkStorageBuckets | undefined {
  if (!bulkStorageConfig.enabled) {
    return;
  }

  const stagingBucketName = `${ClusterBasename}-${xns.logicalName}-bulk-staging`;
  const committedBucketName = `${ClusterBasename}-${xns.logicalName}-bulk-committed`;
  const saName = `${ClusterBasename}-${xns.logicalName}-bulk-sa`;

  // TODO(#3429): review other bucket configs
  const bucketServiceAccount = new gcp.serviceaccount.Account(saName, {
    accountId: saName,
    displayName: 'Service Account for Bulk-Storage Bucket Read/Write Access',
  });
  const hmacKey = new gcp.storage.HmacKey(
    `${saName}-hmac`,
    {
      serviceAccountEmail: bucketServiceAccount.email,
    },
    { dependsOn: [bucketServiceAccount] }
  );

  const staging = new gcp.storage.Bucket(stagingBucketName, {
    name: stagingBucketName,
    location: GcpRegion,
    ...(forceDestroy ? { forceDestroy: true } : {}),
  });
  new gcp.storage.BucketIAMMember(
    `${stagingBucketName}-sa-role`,
    {
      bucket: staging.name,
      role: 'roles/storage.objectUser',
      member: pulumi.interpolate`serviceAccount:${bucketServiceAccount.email}`,
    },
    { dependsOn: [staging, bucketServiceAccount] }
  );
  const committed = new gcp.storage.Bucket(committedBucketName, {
    name: committedBucketName,
    location: GcpRegion,
    ...(forceDestroy ? { forceDestroy: true } : {}),
  });
  new gcp.storage.BucketIAMMember(
    `${committedBucketName}-sa-role-creator`,
    {
      bucket: committed.name,
      role: 'roles/storage.objectCreator',
      member: pulumi.interpolate`serviceAccount:${bucketServiceAccount.email}`,
    },
    { dependsOn: [committed, bucketServiceAccount] }
  );
  new gcp.storage.BucketIAMMember(
    `${committedBucketName}-sa-role-reader`,
    {
      bucket: committed.name,
      role: 'roles/storage.objectViewer',
      member: pulumi.interpolate`serviceAccount:${bucketServiceAccount.email}`,
    },
    { dependsOn: [committed, bucketServiceAccount] }
  );

  const accessKey = hmacKey.accessId;
  const secretAccessKey = hmacKey.secret;

  const secretName = 'splice-app-bulk-storage-credentials';
  const secret = new k8s.core.v1.Secret(
    `${xns.logicalName}-bulk-storage-credentials`,
    {
      metadata: {
        name: secretName,
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        accessKey: accessKey.apply(k => btoa(k || '')),
        secretAccessKey: secretAccessKey.apply(k => btoa(k || '')),
      },
    },
    {
      dependsOn: [xns.ns, hmacKey],
    }
  );

  return {
    staging: {
      bucket: staging,
      region: GcpRegion,
      secret,
    },
    committed: {
      bucket: committed,
      region: GcpRegion,
      secret,
    },
  };
}

// Helm values of the splice-scan chart for the buckets created by installScanBulkStorage.
export function scanBulkStorageValues(buckets: BulkStorageBuckets | undefined): ChartValues {
  if (!buckets) {
    return {};
  }
  const values = (b: BulkStorageBucket) => ({
    region: b.region,
    bucketName: b.bucket.name,
    endpoint: 'https://storage.googleapis.com', // gcs endpoint for s3
    secretName: b.secret.metadata.name,
  });
  return {
    bulkStorage: {
      staging: values(buckets.staging),
      committed: values(buckets.committed),
    },
  };
}

// The resources the splice-scan chart needs before it starts with bulk storage.
export function scanBulkStorageDependencies(
  buckets: BulkStorageBuckets | undefined
): pulumi.Resource[] {
  return buckets
    ? [
        buckets.staging.secret,
        buckets.staging.bucket,
        buckets.committed.secret,
        buckets.committed.bucket,
      ]
    : [];
}

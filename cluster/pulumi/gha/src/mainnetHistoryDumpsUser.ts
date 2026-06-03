// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';

export type MainnetHistoryDumpsUserConfig = {
  /** GCS bucket name that the SA is granted access to */
  bucket: string;
  /** Project number that hosts the GitHub WIF pool */
  wifProjectNumber: string;
  /** WIF pool id */
  wifPoolId: string;
  /** GitHub repos (full "org/name") allowed to impersonate the SA via WIF */
  githubRepositories: string[];
};

/**
 * Creates a dedicated service account that GHA impersonate via WIF
 * to use objects in `gs://<config.bucket>`.
 */
export function manageMainnetHistoryDumpsUser(
  projectId: string,
  config: MainnetHistoryDumpsUserConfig
): pulumi.Resource[] {
  const resources: pulumi.Resource[] = [];

  const sa = new gcp.serviceaccount.Account('mainnet-history-dumps-user', {
    accountId: 'mainnet-history-dumps-user',
    displayName: 'Mainnet History Dumps User (Pulumi managed)',
    description: `Service account for GitHub Actions to read/write gs://${config.bucket}. Managed via Pulumi, do not modify manually.`,
    project: projectId,
  });
  resources.push(sa);

  const bucketBinding = new gcp.storage.BucketIAMMember(
    'mainnet-history-dumps-bucket-user',
    {
      bucket: config.bucket,
      role: 'roles/storage.objectUser',
      member: pulumi.interpolate`serviceAccount:${sa.email}`,
    },
    { dependsOn: sa }
  );
  resources.push(bucketBinding);

  // One WIF binding per allowed GitHub repo.
  for (const repo of config.githubRepositories) {
    // Sanitize the repo names for use as a Pulumi resource name.
    const safe = repo.toLowerCase().replace(/[^a-zA-Z0-9-]/g, '-');
    const wifBinding = new gcp.serviceaccount.IAMMember(
      `mainnet-history-dumps-user-github-wif-${safe}`,
      {
        serviceAccountId: sa.name,
        role: 'roles/iam.workloadIdentityUser',
        member: pulumi.interpolate`principalSet://iam.googleapis.com/projects/${config.wifProjectNumber}/locations/global/workloadIdentityPools/${config.wifPoolId}/attribute.repository/${repo}`,
      },
      { dependsOn: sa }
    );
    resources.push(wifBinding);
  }

  return resources;
}

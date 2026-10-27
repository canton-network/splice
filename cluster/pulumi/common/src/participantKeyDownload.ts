// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// For periodic participant identities backups we must be able to download the participant's private keys,
// unless the participant uses KMS.
export function checkKeyDownloadForPeriodicBackups(
  nodeName: string,
  {
    usesKms,
    enableKeyDownload,
    periodicBackups,
  }: { usesKms: boolean; enableKeyDownload: boolean; periodicBackups: boolean }
): void {
  if (periodicBackups && !usesKms && !enableKeyDownload) {
    throw new Error(
      `${nodeName}: periodic participant identities backups require downloading the participant's private keys, ` +
        'but key downloads are disabled and the participant does not use KMS. ' +
        'Set `participant.enableKeyDownload: true` or configure KMS for this node in config.yaml.'
    );
  }
}

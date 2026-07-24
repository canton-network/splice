// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.metrics

import com.digitalasset.canton.metrics.{
  DecryptionHistograms,
  SigningHistograms,
  DbStorageHistograms,
}

final case class SpliceHistograms(
    dbStorageHistograms: DbStorageHistograms,
    signingHistograms: SigningHistograms,
    decryptionHistograms: DecryptionHistograms,
)

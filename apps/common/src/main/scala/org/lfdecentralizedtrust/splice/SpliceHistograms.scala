package org.lfdecentralizedtrust.splice

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

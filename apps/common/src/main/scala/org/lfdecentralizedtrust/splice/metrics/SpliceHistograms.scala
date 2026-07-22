package org.lfdecentralizedtrust.splice.metrics

import com.daml.metrics.api.MetricName
import com.digitalasset.canton.metrics.{
  DecryptionHistograms,
  SigningHistograms,
  DbStorageHistograms,
}

final case class SpliceHistograms(
    parent: MetricName,
    dbStorageHistograms: DbStorageHistograms,
)(implicit histogramInventory: com.daml.metrics.api.HistogramInventory) {
  val signingHistograms = new SigningHistograms(parent)(histogramInventory)
  val decryptionHistograms = new DecryptionHistograms(parent)(histogramInventory)
}

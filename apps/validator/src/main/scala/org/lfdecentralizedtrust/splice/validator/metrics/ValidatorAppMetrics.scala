// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.metrics

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.logging.NamedLoggerFactory
import org.lfdecentralizedtrust.splice.BaseSpliceMetrics
import org.lfdecentralizedtrust.splice.metrics.{ScanConnectionMetrics, SpliceHistograms}

/** Modelled after [[com.digitalasset.canton.synchronizer.metrics.DomainMetrics]].
  */
class ValidatorAppMetrics(
    metricsFactory: LabeledMetricsFactory,
    histograms: SpliceHistograms,
    loggerFactory: NamedLoggerFactory,
) extends BaseSpliceMetrics("validator", metricsFactory, histograms, loggerFactory) {
  val scanConnections = new ScanConnectionMetrics(metricsFactory)
}

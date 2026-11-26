// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.metrics

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory, Meter}
import com.daml.metrics.api.MetricQualification.{Debug, Traffic}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class UpdateHistoryPruningMetrics(metricsFactory: LabeledMetricsFactory)(implicit
    metricsContext: MetricsContext
) extends AutoCloseable {
  private val prefix: MetricName =
    SpliceMetrics.MetricsPrefix :+ "scan" :+ "pruning"

  private val updateHistoryDaysDropped: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "update_history_days_dropped",
      summary = "Number of days of update history partitions dropped by pruning",
      qualification = Traffic,
    )
  )(metricsContext)

  private val updateHistoryPrunedUpTo: Gauge[Long] = metricsFactory.gauge(
    MetricInfo(
      name = prefix :+ "update_history_pruned_up_to_seconds",
      summary = "End of the most recently dropped day of update history, in epoch seconds",
      qualification = Debug,
    ),
    -1L,
  )(metricsContext)

  def recordDropped(prunedUpTo: CantonTimestamp): Unit = {
    updateHistoryDaysDropped.mark()
    updateHistoryPrunedUpTo.updateValue(prunedUpTo.getEpochSecond)
  }

  override def close(): Unit = updateHistoryPrunedUpTo.close()
}

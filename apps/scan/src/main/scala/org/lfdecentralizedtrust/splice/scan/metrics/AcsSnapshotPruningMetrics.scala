// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.metrics

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory, Meter}
import com.daml.metrics.api.MetricQualification.{Debug, Traffic}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class AcsSnapshotPruningMetrics(metricsFactory: LabeledMetricsFactory)(implicit
    metricsContext: MetricsContext
) extends AutoCloseable {
  private val prefix: MetricName =
    SpliceMetrics.MetricsPrefix :+ "scan" :+ "pruning"

  private val snapshotsDropped: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "snapshots_dropped",
      summary = "Number of ACS snapshots in per-snapshot tables dropped by pruning",
      qualification = Traffic,
    )
  )(metricsContext)

  private val snapshotsPrunedUpTo: Gauge[Long] = metricsFactory.gauge(
    MetricInfo(
      name = prefix :+ "snapshots_pruned_up_to_seconds",
      summary = "Record time of the most recently dropped ACS snapshot, in epoch seconds",
      qualification = Debug,
    ),
    -1L,
  )(metricsContext)

  def recordDropped(snapshotRecordTime: CantonTimestamp): Unit = {
    snapshotsDropped.mark()
    snapshotsPrunedUpTo.updateValue(snapshotRecordTime.getEpochSecond)
  }

  override def close(): Unit = snapshotsPrunedUpTo.close()
}

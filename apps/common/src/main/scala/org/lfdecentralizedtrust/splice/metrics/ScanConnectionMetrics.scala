// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.metrics

import com.daml.metrics.api.MetricHandle.{LabeledMetricsFactory, Meter, Timer}
import com.daml.metrics.api.MetricQualification.{Latency, Traffic}
import com.daml.metrics.api.MetricsContext.Implicits.empty
import com.daml.metrics.api.{MetricInfo, MetricName}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class ScanConnectionMetrics(metricsFactory: LabeledMetricsFactory) {

  private val prefix: MetricName =
    SpliceMetrics.MetricsPrefix :+ "validator" :+ "scan"

  private val perConnectionLabels: Map[String, String] = Map(
    "scan_connection" -> "The scan connection (host:port) handling the request",
    "request" -> "Name of the HTTP/gRPC command being issued",
  )

  /** Latency of a single HTTP/gRPC call to one scan node. */
  val latencyPerConnection: Timer =
    metricsFactory.timer(
      MetricInfo(
        name = prefix :+ "per_connection_latency",
        summary = "Latency of a single request to one scan connection",
        qualification = Latency,
        labelsWithDescription = perConnectionLabels,
      )
    )

  /** Count of failed HTTP/gRPC calls to one scan node. */
  val failuresPerConnection: Meter =
    metricsFactory.meter(
      MetricInfo(
        name = prefix :+ "per_connection_errors",
        summary = "Count of failed requests to a scan connection",
        qualification = Traffic,
        labelsWithDescription = perConnectionLabels,
      )
    )

  /** End-to-end latency of a BFT read across the f+1 scan connections, including
    * retries and the consensus check.
    */
  val bftReadLatency: Timer =
    metricsFactory.timer(
      MetricInfo(
        name = prefix :+ "bft_read_latency",
        summary = "End-to-end latency of a BFT read across scan connections",
        qualification = Latency,
      )
    )

  /** Count of BFT reads that failed (consensus not reached, not enough scans
    * available, or any underlying transport error).
    */
  val bftCallFailures: Meter =
    metricsFactory.meter(
      MetricInfo(
        name = prefix :+ "bft_errors",
        summary = "Count of failed BFT reads",
        qualification = Traffic,
      )
    )
}

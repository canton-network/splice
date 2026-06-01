// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.MetricHandle.Gauge.CloseableGauge
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.{MetricInfo, MetricsContext}

import java.util.concurrent.atomic.AtomicReference

/** A collection of supplier-based gauges sharing the same [[MetricInfo]] but reporting under
  * different [[MetricsContext]]s
  *
  * Each individual gauge has its own lifecycle and can be closed independently via the
  * returned [[CloseableGauge]]. Closing the [[MultiGauge]] itself closes any gauge that has
  * not yet been closed individually.
  */
final class MultiGauge[T] private (
    metricsFactory: LabeledMetricsFactory,
    info: MetricInfo,
    valueSupplier: T => Long,
) extends AutoCloseable {

  private val registered: AtomicReference[Seq[CloseableGauge]] =
    new AtomicReference(Seq.empty)

  def register(target: T)(implicit context: MetricsContext): CloseableGauge = {
    val underlying = metricsFactory.closeableGaugeWithSupplier[Long](
      info,
      () => valueSupplier(target),
    )
    val wrapped: CloseableGauge = new CloseableGauge { self =>
      override def info: MetricInfo = underlying.info
      override def metricType: String = underlying.metricType
      override def close(): Unit = {
        underlying.close()
        val _ = registered.getAndUpdate(_.filterNot(_ eq self))
      }
    }
    val _ = registered.getAndUpdate(_.prepended(wrapped))
    wrapped
  }

  override def close(): Unit = {
    val toClose = registered.getAndSet(Seq.empty)
    toClose.foreach(_.close())
  }
}

object MultiGauge {
  def long[T](
      metricsFactory: LabeledMetricsFactory,
      info: MetricInfo,
  )(valueSupplier: T => Long): MultiGauge[T] =
    new MultiGauge[T](metricsFactory, info, valueSupplier)
}

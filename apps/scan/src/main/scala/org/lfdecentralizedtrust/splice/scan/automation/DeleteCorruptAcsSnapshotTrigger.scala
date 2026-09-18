// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.metrics.api.MetricHandle.{Counter, Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.{Debug, Traffic}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, LifeCycle, SyncCloseable}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.scan.automation.DeleteCorruptAcsSnapshotTrigger.CorruptAcsSnapshotsMetrics

import scala.concurrent.{ExecutionContext, Future}

class DeleteCorruptAcsSnapshotTrigger(
    store: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    metricsContext: MetricsContext,
    protected val context: TriggerContext,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    mat: Materializer,
    // we always return 1 task, so PollingParallelTaskExecutionTrigger in effect does nothing in parallel
) extends PollingParallelTaskExecutionTrigger[DeleteCorruptAcsSnapshotTrigger.Task] {

  private val historyMetrics = new CorruptAcsSnapshotsMetrics(context.metricsFactory)(
    metricsContext
  )

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[DeleteCorruptAcsSnapshotTrigger.Task]] = {
    if (!updateHistory.isReady) {
      Future.successful(Seq.empty)
    } else if (updateHistory.corruptAcsSnapshotsDeleted) {
      Future.successful(Seq.empty)
    } else {
      for {
        migrations <- updateHistory.migrationsWithCorruptSnapshots()
      } yield migrations.lastOption match {
        case Some(migrationToClean) =>
          historyMetrics.completed.updateValue(0)
          Seq(DeleteCorruptAcsSnapshotTrigger.Task(migrationToClean))
        case None =>
          updateHistory.markCorruptAcsSnapshotsDeleted()
          historyMetrics.completed.updateValue(1)
          Seq.empty
      }
    }
  }

  override protected def completeTask(task: DeleteCorruptAcsSnapshotTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = task match {
    case DeleteCorruptAcsSnapshotTrigger.Task(migrationId) =>
      for {
        lastSnapshotO <- store.lookupSnapshotAtOrBefore(migrationId, CantonTimestamp.MaxValue)
        lastSnapshot = lastSnapshotO.getOrElse(
          throw new RuntimeException("Task should never become stale")
        )
        _ <- store.deleteSnapshot(lastSnapshot)
      } yield {
        historyMetrics.count.inc()
        historyMetrics.latestRecordTime.updateValue(
          lastSnapshot.snapshotRecordTime
        )
        TaskSuccess(
          s"Successfully deleted snapshot $lastSnapshot."
        )
      }
  }

  override protected def isStaleTask(task: DeleteCorruptAcsSnapshotTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync() :+
      SyncCloseable("corrupt_acs_snapshots_metrics", LifeCycle.close(historyMetrics)(logger))
}

object DeleteCorruptAcsSnapshotTrigger {

  case class Task(
      migrationId: Long
  ) extends PrettyPrinting {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("migrationId", _.migrationId)
    )
  }

  class CorruptAcsSnapshotsMetrics(metricsFactory: LabeledMetricsFactory)(implicit
      metricsContext: MetricsContext
  ) extends AutoCloseable {

    private val corruptAcsSnapshotsPrefix: MetricName =
      SpliceMetrics.MetricsHistoryPrefix :+ "corrupt-acs-snapshots"

    val latestRecordTime: Gauge[CantonTimestamp] =
      SpliceMetrics.cantonTimestampGauge(
        metricsFactory,
        MetricInfo(
          name = corruptAcsSnapshotsPrefix :+ "latest-record-time",
          summary = "The record time of the latest corrupt snapshot that has been deleted",
          Traffic,
        ),
        initial = CantonTimestamp.MinValue,
      )(metricsContext)

    val count: Counter =
      metricsFactory.counter(
        MetricInfo(
          name = corruptAcsSnapshotsPrefix :+ "count",
          summary = "The number of corrupt ACS snapshots deleted",
          Traffic,
        )
      )(metricsContext)

    val completed: Gauge[Int] =
      metricsFactory.gauge(
        MetricInfo(
          name = corruptAcsSnapshotsPrefix :+ "completed",
          summary = "Whether all corrupt snapshots are deleted (1) or not (0)",
          Debug,
        ),
        initial = 0,
      )(metricsContext)

    override def close(): Unit = {
      latestRecordTime.close()
      completed.close()
    }
  }
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.scan.automation.PruneUpdateHistoryTrigger.PrunableDay
import org.lfdecentralizedtrust.splice.scan.metrics.UpdateHistoryPruningMetrics
import org.lfdecentralizedtrust.splice.scan.store.bulk.{BulkObjectKind, BulkStorageReader}
import org.lfdecentralizedtrust.splice.store.UpdateHistory

import java.time.{LocalDate, ZoneOffset}
import scala.concurrent.{ExecutionContextExecutor, Future}

class PruneUpdateHistoryTrigger(
    updateHistory: UpdateHistory,
    bulkStorage: BulkStorageReader,
    protected val context: TriggerContext,
)(implicit
    ec: ExecutionContextExecutor,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[PrunableDay] {

  private val pruningMetrics = new UpdateHistoryPruningMetrics(context.metricsFactory)(
    MetricsContext("current_migration_id" -> updateHistory.domainMigrationId.toString)
  )

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[PrunableDay]] =
    if (!updateHistory.isReady) Future.successful(Seq.empty)
    else
      for {
        committed <- bulkStorage.getCommittedProgressTimestamp(Some(BulkObjectKind.Updates))
        days <- updateHistory.listDatesWithAllPartitions()
      } yield PruneUpdateHistoryTrigger
        .prunable(days, updateHistory.startAnalyzableTimeWindow, committed)
        .map(PrunableDay(_))
        .toList

  override protected def completeTask(task: PrunableDay)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    updateHistory.dropPartitions(task.day).map { _ =>
      pruningMetrics.recordDropped(PruneUpdateHistoryTrigger.startOf(task.day.plusDays(1)))
      TaskSuccess(s"Dropped the update history partitions of ${task.day}")
    }

  override protected def isStaleTask(task: PrunableDay)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync() :+
      SyncCloseable("UpdateHistoryPruningMetrics", pruningMetrics.close())
}

object PruneUpdateHistoryTrigger {

  final case class PrunableDay(day: LocalDate) extends PrettyPrinting {
    override def pretty: Pretty[PrunableDay] = prettyOfClass(param("day", _.day.toString.unquoted))
  }

  def startOf(day: LocalDate): CantonTimestamp =
    CantonTimestamp.assertFromInstant(day.atStartOfDay(ZoneOffset.UTC).toInstant)

  def prunable(
      days: Set[LocalDate],
      startAtw: Option[CantonTimestamp],
      committedInBulkStorage: CantonTimestamp,
  ): Option[LocalDate] =
    startAtw.flatMap { atw =>
      val upTo = if (atw < committedInBulkStorage) atw else committedInBulkStorage
      days.filter(day => startOf(day.plusDays(1)) <= upTo).minOption
    }
}

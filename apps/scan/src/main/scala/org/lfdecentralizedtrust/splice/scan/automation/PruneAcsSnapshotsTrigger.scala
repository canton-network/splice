// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.scan.metrics.AcsSnapshotPruningMetrics
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.PerTableAcsSnapshot
import org.lfdecentralizedtrust.splice.store.KeyValueStore
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider.timestampCodec

import scala.concurrent.{ExecutionContextExecutor, Future}

class PruneAcsSnapshotsTrigger(
    store: AcsSnapshotStore,
    storageConfig: ScanStorageConfig,
    kvStore: KeyValueStore,
    protected val context: TriggerContext,
)(implicit
    ec: ExecutionContextExecutor,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[PerTableAcsSnapshot] {

  private val pruningMetrics = new AcsSnapshotPruningMetrics(context.metricsFactory)(
    MetricsContext(
      "current_migration_id" -> store.currentMigrationId.toString
    )
  )

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[PerTableAcsSnapshot]] =
    store.updateHistory.startAnalyzableTimeWindow
      .filter(_ => store.updateHistory.isReady)
      .map(startAtw =>
        store
          .lookupPerTableSnapshotsBefore(startAtw)
          // one-at-a-time to spread out the DB load when a large # of
          // snapshots suddenly become pruning candidates (e.g. pruning
          // has just been enabled)
          .map(PruneAcsSnapshotsTrigger.prunable(_, storageConfig).take(1))
      )
      .getOrElse(Future.successful(Seq.empty))

  override protected def completeTask(task: PerTableAcsSnapshot)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    for {
      _ <- store.deleteSnapshot(task)
      _ <- kvStore.setValue(PruneAcsSnapshotsTrigger.prunedUpToKey, task.snapshotRecordTime)
    } yield {
      pruningMetrics.recordDropped(task.snapshotRecordTime)
      TaskSuccess(s"Dropped ACS snapshot ${task.snapshotRecordTime}")
    }

  override protected def isStaleTask(task: PerTableAcsSnapshot)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync() :+
      SyncCloseable("AcsSnapshotPruningMetrics", pruningMetrics.close())
}

object PruneAcsSnapshotsTrigger {

  val prunedUpToKey = "snapshots_pruned_up_to"

  def prunable(
      snapshotsBeforeAtw: Seq[PerTableAcsSnapshot],
      storageConfig: ScanStorageConfig,
  ): Seq[PerTableAcsSnapshot] =
    snapshotsBeforeAtw.filterNot(s =>
      storageConfig.shouldDumpSnapshotToBulkStorage(s.snapshotRecordTime)
    )
}

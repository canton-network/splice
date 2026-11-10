// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

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
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.PerTableAcsSnapshot

import scala.concurrent.{ExecutionContextExecutor, Future}

class PruneAcsSnapshotsTrigger(
    store: AcsSnapshotStore,
    storageConfig: ScanStorageConfig,
    protected val context: TriggerContext,
)(implicit
    ec: ExecutionContextExecutor,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[PerTableAcsSnapshot] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[PerTableAcsSnapshot]] =
    store.updateHistory.startAnalyzableTimeWindow
      .filter(_ => store.updateHistory.isReady)
      .map(startAtw =>
        store
          .lookupPerTableSnapshotsBefore(startAtw)
          .map(PruneAcsSnapshotsTrigger.prunable(_, storageConfig).take(1))
      )
      .getOrElse(Future.successful(Seq.empty))

  override protected def completeTask(task: PerTableAcsSnapshot)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    store
      .deleteSnapshot(task)
      .map(_ => TaskSuccess(s"Dropped ACS snapshot ${task.snapshotRecordTime}"))

  override protected def isStaleTask(task: PerTableAcsSnapshot)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)
}

object PruneAcsSnapshotsTrigger {

  def prunable(
      snapshotsBeforeAtw: Seq[PerTableAcsSnapshot],
      storageConfig: ScanStorageConfig,
  ): Seq[PerTableAcsSnapshot] =
    snapshotsBeforeAtw.filterNot(s =>
      storageConfig.shouldDumpSnapshotToBulkStorage(s.snapshotRecordTime)
    )
}

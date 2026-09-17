// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.automation.{
  SourceBasedTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.PerTableAcsSnapshot

import scala.concurrent.{ExecutionContextExecutor, Future}

class AcsSnapshotIndexTrigger(
    store: AcsSnapshotStore,
    protected val context: TriggerContext,
)(implicit
    ec: ExecutionContextExecutor,
    override val tracer: Tracer,
    mat: Materializer,
) extends SourceBasedTrigger[PerTableAcsSnapshot] {

  /** The source from which to consume tasks. */
  override protected def source(implicit
      traceContext: TraceContext
  ): Source[PerTableAcsSnapshot, NotUsed] = Source
    .repeat(())
    .mapAsync(parallelism = 1)(_ => store.lookupOldestUnindexedSnapshot())
    .collect { case Some(snapshot) => snapshot }

  override protected def completeTask(task: PerTableAcsSnapshot)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = store
    .indexSnapshotStakeholdersTable(task)
    .map(_ => TaskSuccess(s"Successfully indexed tables of snapshot ${task.snapshotRecordTime}"))

  override protected def isStaleTask(task: PerTableAcsSnapshot)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    store.lookupOldestUnindexedSnapshot().map {
      case None => false
      // if the oldest has changed, the task is stale
      case Some(oldest) => oldest.snapshotRecordTime != task.snapshotRecordTime
    }
  }

}

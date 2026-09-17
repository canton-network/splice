// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{SqlIndexInitializationTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import com.digitalasset.canton.discard.Implicits.DiscardOps
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  AcsSnapshotTableDDL,
  PerTableAcsSnapshot,
}
import com.digitalasset.canton.lifecycle.FutureUnlessShutdownImpl.*

import scala.concurrent.{ExecutionContextExecutor, Future}

class AcsSnapshotIndexTrigger(storage: DbStorage, store: AcsSnapshotStore, context: TriggerContext)(
    implicit
    ec: ExecutionContextExecutor,
    override val tracer: Tracer,
    mat: Materializer,
) extends SqlIndexInitializationTrigger[AcsSnapshot](storage, context) {

  override protected def retrieveNextIndexTasks()(implicit
      tc: TraceContext
  ): FutureUnlessShutdown[Seq[(SqlIndexInitializationTrigger.IndexAction, AcsSnapshot)]] = {
    store
      .lookupOldestUnindexedSnapshot()
      .map { snapshot =>
        // Statements are safe to retry because of `if not exists`
        indexesToCreate(snapshot).map(_ -> snapshot)
      }
      .value
      .map {
        case Some(result) => result
        case None => Seq.empty
      }
  }

  private def indexesToCreate(snapshot: AcsSnapshot) = Seq(
    SqlIndexInitializationTrigger.IndexAction.Create(
      AcsSnapshotTableIndexes
        .stakeholderIndexName(snapshot.historyId, snapshot.snapshotRecordTime),
      AcsSnapshotTableIndexes.stakeholderIndexAction(snapshot.historyId, snapshot.snapshotRecordTime),
    ),
    SqlIndexInitializationTrigger.IndexAction.Create(
      AcsSnapshotTableIndexes
        .stakeholderTemplateIdIndexName(snapshot.historyId, snapshot.snapshotRecordTime),
      AcsSnapshotTableIndexes
        .stakeholderTemplateIdIndexAction(snapshot.historyId, snapshot.snapshotRecordTime),
    ),
  )

  private val createdIndexesMap =
    new java.util.concurrent.ConcurrentHashMap[CantonTimestamp, Set[String]]()
  override protected def onActionCompleted(
      action: SqlIndexInitializationTrigger.IndexAction,
      // We could also extract the snapshotRecordTime from the action,
      // but that will require regex-ing the index name, which is significantly more error-prone than this.
      meta: AcsSnapshot,
  )(implicit tc: TraceContext): Future[Unit] = {
    val snapshotRecordTime = meta.snapshotRecordTime
    val createdIndexesForSnapshot =
      createdIndexesMap.compute(
        snapshotRecordTime,
        (_, createdIndexes) => createdIndexes + action.indexName,
      )

    // Once all the indexes are created, we can mark the snapshot as indexed
    if (createdIndexesForSnapshot.size == indexesToCreate(meta).size) {
      for {
        _ <- store.markSnapshotAsIndexed(snapshotRecordTime)
      } yield {
        // Cleanup to avoid filling it up forever
        createdIndexesMap.remove(snapshotRecordTime).discard
      }
    } else {
      Future.unit
    }
  }

}

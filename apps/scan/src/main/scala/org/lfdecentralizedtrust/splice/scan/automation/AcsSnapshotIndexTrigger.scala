package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{SqlIndexInitializationTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.AcsTableDDL
import com.digitalasset.canton.lifecycle.FutureUnlessShutdownImpl.*

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Try

class AcsSnapshotIndexTrigger(storage: DbStorage, store: AcsSnapshotStore, context: TriggerContext)(
    implicit
    ec: ExecutionContextExecutor,
    override val tracer: Tracer,
    mat: Materializer,
) extends SqlIndexInitializationTrigger(storage, context) {

  override protected def retrieveNextIndexTasks()(implicit
      tc: TraceContext
  ): FutureUnlessShutdown[Seq[SqlIndexInitializationTrigger.IndexAction]] = {
    store
      .lookupOldestUnindexedSnapshot()
      .map { snapshot =>
        // Statements are safe to retry because of `if not exists`
        Seq(
          SqlIndexInitializationTrigger.IndexAction.Create(
            AcsTableDDL
              .stakeholderIndexName(snapshot.historyId, snapshot.snapshotRecordTime),
            AcsTableDDL.stakeholderIndexAction(snapshot.historyId, snapshot.snapshotRecordTime),
          ),
          SqlIndexInitializationTrigger.IndexAction.Create(
            AcsTableDDL
              .stakeholderTemplateIdIndexName(snapshot.historyId, snapshot.snapshotRecordTime),
            AcsTableDDL
              .stakeholderTemplateIdIndexAction(snapshot.historyId, snapshot.snapshotRecordTime),
          ),
        )
      }
      .value
      .map {
        case Some(result) => result
        case None => Seq.empty
      }
  }

  override protected def onActionCompleted(
      action: SqlIndexInitializationTrigger.IndexAction
  )(implicit tc: TraceContext): Future[Unit] = {
    for {
      snapshotRecordTime <- Future.fromTry(
        Try(
          CantonTimestamp.assertFromInstant(
            Instant.ofEpochMilli(action.indexName.split("_").last.toLong)
          )
        )
      )
      _ <- store.markSnapshotAsIndexed(snapshotRecordTime)
    } yield ()
  }

}

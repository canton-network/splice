// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.config.DbConfig
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.{FutureUnlessShutdown, *}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.db.AdvisoryLocks
import org.lfdecentralizedtrust.splice.store.db.SqlIndexInitializer.{
  IndexAction,
  IndexStatus,
  defaultIndexActions,
  getIndexStatusAction,
}
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import scala.util.{Failure, Success}

/** A trigger that asynchronously creates or drops SQL indexes at application startup.
  *
  * Indexes are processed in the order they are provided.
  * If a created index becomes invalid, it is dropped and recreated.
  */
class SqlIndexInitializationTrigger(
    storage: DbStorage,
    protected val context: TriggerContext,
    indexActions: List[IndexAction],
)(implicit ec: ExecutionContextExecutor, override val tracer: Tracer, mat: Materializer)
    extends PollingParallelTaskExecutionTrigger[SqlIndexInitializationTrigger.Task]
    with HasCloseContext {
  import SqlIndexInitializationTrigger.*

  assert(
    indexActions.distinct == indexActions,
    "Index actions must be unique.",
  )
  private val remainingActions: AtomicReference[List[IndexAction]] = new AtomicReference(
    indexActions
  )
  private[automation] val remainingActionsEmpty: Promise[Unit] = Promise()

  private def nextTask(actions: List[IndexAction])(implicit
      tc: TraceContext
  ): FutureUnlessShutdown[Seq[Task]] =
    actions.headOption match {
      case None =>
        FutureUnlessShutdown.pure(Seq.empty)
      case Some(head) =>
        for {
          headStatus <- storage.query(
            getIndexStatusAction(head.indexName),
            "getIndexStatusAction",
          )
        } yield (head, headStatus) match {
          case (_, IndexStatus.InProgress(pid)) =>
            logger.info(
              s"Index ${head.indexName} is being built by backend process $pid, skipping."
            )
            // Do not mess with the index if it is being built.
            // Return no task, causing the trigger to try again after the next polling interval.
            Seq.empty

          case (IndexAction.Create(indexName, _), IndexStatus.Valid) =>
            logger.info(s"Index $indexName should be created and is valid, skipping.")
            Seq(Task.ConfirmActionCompleted(head))

          case (IndexAction.Create(indexName, _), IndexStatus.DoesNotExist) =>
            logger.info(s"Index $indexName should be created and does not exist, creating it.")
            Seq(Task.ExecuteAction(head))

          case (IndexAction.Create(indexName, _), IndexStatus.Invalid) =>
            logger.warn(s"Index $indexName should be created and is invalid, dropping it.")
            Seq(Task.ExecuteAction(IndexAction.Drop(indexName)))

          case (IndexAction.Drop(indexName), IndexStatus.DoesNotExist) =>
            logger.info(s"Index $indexName should be dropped and does not exist, skipping.")
            Seq(Task.ConfirmActionCompleted(head))

          case (IndexAction.Drop(indexName), IndexStatus.Valid) =>
            logger.info(s"Index $indexName should be dropped and is valid, dropping.")
            Seq(Task.ExecuteAction(head))

          case (IndexAction.Drop(indexName), IndexStatus.Invalid) =>
            logger.warn(s"Index $indexName should be dropped and is invalid, dropping.")
            Seq(Task.ExecuteAction(head))
        }
    }

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[SqlIndexInitializationTrigger.Task]] = {
    storage.dbConfig match {
      case _: DbConfig.Postgres =>
        nextTask(remainingActions.get())
          .failOnShutdownToAbortException("Retrieve SqlIndexInitializationTrigger tasks")
      case _ =>
        // We only really support Postgres in our apps.
        throw new RuntimeException(s"Unsupported dbConfig ${storage.dbConfig}")
    }
  }

  override protected def isStaleTask(task: SqlIndexInitializationTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    // We are using "if not exists" for index creation and dropping.
    // Statements should be safe to retry, and failing tasks should be reported as errors.
    Future.successful(false)
  }

  override protected def completeTask(task: SqlIndexInitializationTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = (task match {
    case Task.ExecuteAction(IndexAction.Drop(indexName)) =>
      logger.info(s"Dropping index $indexName")
      storage
        .queryAndUpdate(
          AdvisoryLocks.withDdlLock(sqlu"drop index concurrently if exists #$indexName"),
          "drop_" + indexName,
        )
        .unwrap
        .map { _ =>
          logger.info(s"Finished dropping index $indexName")
          TaskSuccess(s"Dropped index $indexName")
        }

    case Task.ExecuteAction(IndexAction.Create(indexName, createAction)) =>
      logger.info(s"Creating index $indexName")
      storage
        .queryAndUpdate(AdvisoryLocks.withDdlLock(createAction), "create_" + indexName)
        .unwrap
        .map { _ =>
          logger.info(s"Finished creating index $indexName")
          TaskSuccess(s"Created index $indexName")
        }

    case Task.ConfirmActionCompleted(action) =>
      remainingActions.updateAndGet(_.filterNot(_ == action))
      if (remainingActions.get().isEmpty) {
        remainingActionsEmpty.trySuccess(()).discard
      }
      logger.info(s"Confirmed action completed for index ${action.indexName}")
      Future.successful(TaskSuccess(s"Confirmed action completed for index ${action.indexName}"))
  }).transform {
    case Failure(e: AdvisoryLocks.FailedToAcquireLockException) =>
      // There was a concurrent DDL statement running.
      // The action stays in `remainingActions`, so we retry it on the next poll.
      logger.info(s"Skipping $task, another DDL statement was running currently", e)
      Success(TaskNoop)
    case other => other
  }
}

object SqlIndexInitializationTrigger {

  def apply(
      storage: DbStorage,
      triggerContext: TriggerContext,
      indexActions: List[IndexAction] = defaultIndexActions,
  )(implicit
      ec: ExecutionContextExecutor,
      tracer: Tracer,
      mat: Materializer,
  ): SqlIndexInitializationTrigger = {
    new SqlIndexInitializationTrigger(
      storage,
      triggerContext,
      indexActions,
    )
  }

  sealed trait Task extends Product with Serializable with PrettyPrinting
  object Task {

    final case class ExecuteAction(action: IndexAction) extends Task {
      override def pretty: Pretty[this.type] =
        prettyOfClass(
          param("action", _.action.showType),
          param("index", _.action.indexName.unquoted),
        )
    }

    final case class ConfirmActionCompleted(action: IndexAction) extends Task {
      override def pretty: Pretty[this.type] =
        prettyOfClass(
          param("action", _.action.showType),
          param("index", _.action.indexName.unquoted),
        )
    }
  }
}

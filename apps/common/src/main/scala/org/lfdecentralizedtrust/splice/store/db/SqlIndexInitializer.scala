// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.lifecycle.{CloseContext, FutureUnlessShutdown}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.ExecutionContext

class SqlIndexInitializer(
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {
  import SqlIndexInitializer.*

  def nextStep(action: IndexAction)(implicit
      tc: TraceContext,
      cc: CloseContext,
  ): FutureUnlessShutdown[Step] =
    storage
      .query(getIndexStatusAction(action.indexName), "getIndexStatusAction")
      .map(status => decide(action, status))

  def execute(action: IndexAction)(implicit
      tc: TraceContext,
      cc: CloseContext,
  ): FutureUnlessShutdown[Unit] = action match {
    case IndexAction.Drop(indexName) =>
      logger.info(s"Dropping index $indexName")
      storage
        .queryAndUpdate(
          AdvisoryLocks.withDdlLock(sqlu"drop index concurrently if exists #$indexName"),
          "drop_" + indexName,
        )
        .map(_ => logger.info(s"Finished dropping index $indexName"))

    case IndexAction.Create(indexName, createAction) =>
      logger.info(s"Creating index $indexName")
      storage
        .queryAndUpdate(AdvisoryLocks.withDdlLock(createAction), "create_" + indexName)
        .map(_ => logger.info(s"Finished creating index $indexName"))
  }

  def applyAll(actions: List[IndexAction])(implicit
      tc: TraceContext,
      cc: CloseContext,
  ): FutureUnlessShutdown[Unit] =
    actions.foldLeft(FutureUnlessShutdown.unit)((previous, action) =>
      previous.flatMap(_ => applyOne(action))
    )

  private def applyOne(action: IndexAction)(implicit
      tc: TraceContext,
      cc: CloseContext,
  ): FutureUnlessShutdown[Unit] =
    nextStep(action).flatMap {
      case Step.Done =>
        FutureUnlessShutdown.unit
      case Step.Execute(`action`) =>
        execute(action).flatMap(_ => nextStep(action)).flatMap {
          case Step.Done => FutureUnlessShutdown.unit
          case other =>
            failed(s"Index ${action.indexName} is not done after $action, next step is $other")
        }
      case Step.Execute(other) =>
        failed(s"Index ${action.indexName} is invalid, the trigger would run $other")
      case Step.Wait(pid) =>
        failed(s"Index ${action.indexName} is being built by backend process $pid")
    }

  private def failed(message: String): FutureUnlessShutdown[Unit] =
    FutureUnlessShutdown.failed(new IllegalStateException(message))

  private def decide(action: IndexAction, status: IndexStatus)(implicit
      tc: TraceContext
  ): Step =
    (action, status) match {
      case (_, IndexStatus.InProgress(pid)) =>
        logger.info(
          s"Index ${action.indexName} is being built by backend process $pid, skipping."
        )
        Step.Wait(pid)

      case (IndexAction.Create(indexName, _), IndexStatus.Valid) =>
        logger.info(s"Index $indexName should be created and is valid, skipping.")
        Step.Done

      case (IndexAction.Create(indexName, _), IndexStatus.DoesNotExist) =>
        logger.info(s"Index $indexName should be created and does not exist, creating it.")
        Step.Execute(action)

      case (IndexAction.Create(indexName, _), IndexStatus.Invalid) =>
        logger.warn(s"Index $indexName should be created and is invalid, dropping it.")
        Step.Execute(IndexAction.Drop(indexName))

      case (IndexAction.Drop(indexName), IndexStatus.DoesNotExist) =>
        logger.info(s"Index $indexName should be dropped and does not exist, skipping.")
        Step.Done

      case (IndexAction.Drop(indexName), IndexStatus.Valid) =>
        logger.info(s"Index $indexName should be dropped and is valid, dropping.")
        Step.Execute(action)

      case (IndexAction.Drop(indexName), IndexStatus.Invalid) =>
        logger.warn(s"Index $indexName should be dropped and is invalid, dropping.")
        Step.Execute(action)
    }
}

object SqlIndexInitializer {

  sealed trait Step extends Product with Serializable
  object Step {
    case object Done extends Step
    final case class Execute(action: IndexAction) extends Step
    final case class Wait(pid: Long) extends Step
  }

  sealed trait IndexStatus
  object IndexStatus {

    /** Index is valid and ready for use */
    final case object Valid extends IndexStatus

    /** Index is permanently invalid */
    final case object Invalid extends IndexStatus

    /** Index does not exist */
    final case object DoesNotExist extends IndexStatus

    /** Index is being built by the given background process */
    final case class InProgress(pid: Long) extends IndexStatus
  }

  def getIndexStatusAction(
      indexName: String
  )(implicit
      ec: ExecutionContext
  ): DBIOAction[IndexStatus, NoStream, Effect.Read] = {
    sql"""
      select
        i.indisvalid, pspci.pid
      from
        pg_class c
      join
        pg_namespace n on n.oid = c.relnamespace
      join
        pg_index i on i.indexrelid = c.oid
      left join
        pg_stat_progress_create_index pspci on pspci.index_relid = c.oid
      where
        c.relname = $indexName
        and n.nspname = current_schema
        and c.relkind = 'i'
    """.as[(Boolean, Option[Long])].headOption.map {
      // The status is "in progress" if a backend process exists that is actively building the index
      case Some((_, Some(pid))) => IndexStatus.InProgress(pid)
      case Some((true, None)) => IndexStatus.Valid
      case Some((false, None)) => IndexStatus.Invalid
      case None => IndexStatus.DoesNotExist
    }
  }

  sealed trait IndexAction {
    def indexName: String
  }
  object IndexAction {

    /** Drop this index if it exists. */
    final case class Drop(indexName: String) extends IndexAction

    /** Create this index if it does not exist. */
    final case class Create(
        indexName: String,
        createAction: DBIOAction[Int, NoStream, Effect.Write & Effect.Transactional],
    ) extends IndexAction
  }

  /** Indexes managed by SqlIndexInitializationTrigger
    *  WARNING: partitioned tables (such as all update_history tables) cannot be indexed 'concurrently'.
    */
  val defaultIndexActions: List[IndexAction] = List[IndexAction](
    IndexAction
      .Create(
        indexName = "dso_acs_store_sid_mid_pn_tid_rbio",
        createAction = sqlu"""
          create index concurrently if not exists dso_acs_store_sid_mid_pn_tid_rbio
          on dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name, reward_party)
          where reward_beneficiary_is_observer = false
        """,
      ),
    IndexAction
      .Create(
        indexName = "dso_acs_store_sid_mid_pn_tid_farp",
        createAction = sqlu"""
          create index concurrently if not exists dso_acs_store_sid_mid_pn_tid_farp
          on dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name, featured_app_right_provider)
          where featured_app_right_provider is not null
        """,
      ),
    governanceLockTypeAndSubjectIndexAction("dso_acs_store"),
    governanceLockTypeAndSubjectIndexAction("scan_acs_store"),
    governanceLockTypeAndSubjectIndexAction("user_wallet_acs_store"),
    governanceLockTypeAndSubjectIndexAction("external_party_wallet_acs_store"),
    IndexAction
      .Create(
        indexName = "scan_txlog_store_sid_effat_en_vot",
        createAction = sqlu"""
          create index concurrently if not exists scan_txlog_store_sid_effat_en_vot
          on scan_txlog_store (store_id, coalesce(vote_effective_at, entry_data->'result'->>'completedAt') desc, entry_number desc)
          where entry_type = 'vot'
        """,
      ),
    IndexAction.Drop(indexName = "scan_txlog_store_sid_en_vot"),
  )

  private def governanceLockTypeAndSubjectIndexAction(tableName: String): IndexAction =
    IndexAction.Create(
      indexName = s"${tableName}_sid_mid_glt_gls",
      createAction = sqlu"""
        create index concurrently if not exists #${tableName}_sid_mid_glt_gls
        on #$tableName (store_id, migration_id, governance_lock_type, governance_lock_subject)
        where governance_lock_type is not null
      """,
    )
}

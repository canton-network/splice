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
  ) ++ hashKeyIndexActions

  /** Replaces the indexes on variable-length text and JSON columns by indexes on a fixed-size hash of the value
    * (see `HashIndexedColumn`). Each new index is created before the old one is dropped; queries filter on both
    * the hash and the raw value, so they can use either index in the meantime.
    */
  private def hashKeyIndexActions: List[IndexAction] = {
    def replace(oldIndexName: String, newIndexName: String)(
        createAction: DBIOAction[Int, NoStream, Effect.Write & Effect.Transactional]
    ): List[IndexAction] =
      List[IndexAction](
        IndexAction.Create(newIndexName, createAction),
        IndexAction.Drop(oldIndexName),
      )

    replace("dso_acs_store_sid_mid_pn_tid_cen_exp", "dso_acs_store_sid_mid_pn_tid_cenh_exp")(sqlu"""
        create index concurrently if not exists dso_acs_store_sid_mid_pn_tid_cenh_exp
        on dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name,
                          splice_text_hash(ans_entry_name), contract_expires_at)
        where ans_entry_name is not null and contract_expires_at is not null
      """) ++
      replace("dso_acs_store_sid_mid_pn_tid_scn", "dso_acs_store_sid_mid_pn_tid_scnh")(sqlu"""
        create index concurrently if not exists dso_acs_store_sid_mid_pn_tid_scnh
        on dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name,
                          splice_text_hash(sv_candidate_name))
        where sv_candidate_name is not null
      """) ++
      // There are only a few onboarding requests per candidate party, so the party alone is selective enough;
      // queries recheck the name.
      replace("dso_acs_store_sid_mid_pn_tid_scp_scn", "dso_acs_store_sid_mid_pn_tid_scp")(sqlu"""
        create index concurrently if not exists dso_acs_store_sid_mid_pn_tid_scp
        on dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name, sv_candidate_party)
        where sv_candidate_party is not null
      """) ++
      replace("dso_acs_store_sid_pn_tid_sot", "dso_acs_store_sid_pn_tid_soth")(sqlu"""
        create index concurrently if not exists dso_acs_store_sid_pn_tid_soth
        on dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name,
                          splice_text_hash(sv_onboarding_token))
        where sv_onboarding_token is not null
      """) ++
      replace("dso_acs_store_sid_mid_pn_tid_ah_r", "dso_acs_store_sid_mid_pn_tid_ahh_r")(sqlu"""
        create index concurrently if not exists dso_acs_store_sid_mid_pn_tid_ahh_r
        on dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name,
                          splice_jsonb_hash(action_requiring_confirmation), requester)
        where action_requiring_confirmation is not null
      """) ++
      replace("dso_acs_store_sid_pn_tid_ah_c", "dso_acs_store_sid_pn_tid_ahh_c")(sqlu"""
        create index concurrently if not exists dso_acs_store_sid_pn_tid_ahh_c
        on dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name,
                          splice_jsonb_hash(action_requiring_confirmation), confirmer)
        where action_requiring_confirmation is not null
      """) ++
      replace("sv_acs_store_sid_mid_pn_tid_asicn", "sv_acs_store_sid_mid_pn_tid_asicnh")(sqlu"""
        create index concurrently if not exists sv_acs_store_sid_mid_pn_tid_asicnh
        on sv_acs_store (store_id, migration_id, package_name, template_id_qualified_name,
                         splice_text_hash(sv_candidate_name))
        where sv_candidate_name is not null
      """) ++
      replace("sv_acs_store_sid_mid_pn_tid_vocs", "sv_acs_store_sid_mid_pn_tid_vocsh")(sqlu"""
        create index concurrently if not exists sv_acs_store_sid_mid_pn_tid_vocsh
        on sv_acs_store (store_id, migration_id, package_name, template_id_qualified_name,
                         splice_text_hash(onboarding_secret))
        where onboarding_secret is not null
      """) ++
      replace("validator_acs_store_sid_mid_pn_tid_un", "validator_acs_store_sid_mid_pn_tid_unh")(
        sqlu"""
        create index concurrently if not exists validator_acs_store_sid_mid_pn_tid_unh
        on validator_acs_store (store_id, migration_id, package_name, template_id_qualified_name,
                                splice_text_hash(user_name))
        where user_name is not null
      """
      ) ++
      replace("user_wallet_txlog_store_sid_tid", "user_wallet_txlog_store_sid_tidh")(sqlu"""
        create index concurrently if not exists user_wallet_txlog_store_sid_tidh
        on user_wallet_txlog_store (store_id, splice_text_hash(tracking_id))
        where tracking_id is not null
      """) ++
      List("dso_acs_store", "scan_acs_store").flatMap(tableName =>
        replace(s"${tableName}_sid_mid_glt_gls", s"${tableName}_sid_mid_glt_glsh")(sqlu"""
          create index concurrently if not exists #${tableName}_sid_mid_glt_glsh
          on #$tableName (store_id, migration_id, governance_lock_type, splice_text_hash(governance_lock_subject))
          where governance_lock_type is not null
        """)
      ) ++
      // No query of these stores looks up governance locks by type and subject.
      List[IndexAction](
        IndexAction.Drop("user_wallet_acs_store_sid_mid_glt_gls"),
        IndexAction.Drop("external_party_wallet_acs_store_sid_mid_glt_gls"),
      )
  }
}

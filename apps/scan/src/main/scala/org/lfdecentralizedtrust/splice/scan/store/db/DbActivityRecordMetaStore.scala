// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.google.common.annotations.VisibleForTesting
import org.lfdecentralizedtrust.splice.scan.store.ActivityRecordMetaStore
import org.lfdecentralizedtrust.splice.scan.store.ActivityRecordMetaStore.RoundIngestionStatus
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.jdbc.{GetResult, PostgresProfile}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

object DbActivityRecordMetaStore {

  /** Metadata row for extra-activity ingestion (CIP-0104 extension hook).
    * Mirrors `DbAppActivityRecordStore.AppActivityRecordMetaT` exactly;
    * owned here because the facade is the sole reader/writer of
    * `extra_app_activity_record_meta`.
    */
  final case class ExtraActivityRecordMetaT(
      historyId: Long,
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  )
}

/** Facade over the two concrete activity-record stores: the TBAR app-side
  * `DbAppActivityRecordStore` (fed from verdicts) and the CIP-0104
  * extension hook's `DbExtraActivityRecordStore` (fed from
  * `ActivityRecorder_DsoRecordActivity` exercises). Combines their
  * meta-store surfaces into the shape the `ActivityRecordMetaStore` trait
  * exposes to callers (triggers, HTTP handlers, automation service), so
  * the two sources look like a single unified round-completion view.
  *
  * Asymmetric ownership:
  *   - App-side meta reads delegate to `DbAppActivityRecordStore`, which
  *     still owns its own meta-read methods for internal callers
  *     (`assertCompleteActivity`, `ScanVerdictIngestionService`).
  *   - Extra-side meta reads AND writes live here directly. The extra
  *     store is a thin row-helper for `extra_app_activity_record_store`
  *     and no longer touches `extra_app_activity_record_meta`.
  *
  * Combine semantics:
  *   - `earliestRoundWithCompleteAppActivity`: max of the two sides —
  *     completeness from genesis requires BOTH to have reached the round.
  *     Returns None if either side is None.
  *   - `latestRoundWithCompleteAppActivity`: min of the two — the merged
  *     frontier is bounded by whichever side is slower. Returns None if
  *     either side is None.
  *   - `ingestionStatusForRound`: if either side reports `CannotProvide`,
  *     the facade returns `CannotProvide` (caller should delegate to BFT).
  *     Otherwise `Undetermined` (either or both sides still catching up).
  *   - `startedIngestingAt`: the earlier of the two start times when both
  *     are available; the single non-None value otherwise.
  */
class DbActivityRecordMetaStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
    appActivityRecordStore: DbAppActivityRecordStore,
    val ingestionVersions: DbAppActivityRecordStore.IngestionVersions,
    isFirstSv: Boolean,
    initialRound: Long,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends ActivityRecordMetaStore
    with NamedLogging
    with org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
    with org.lfdecentralizedtrust.splice.store.db.AcsQueries
    with FlagCloseable
    with HasCloseContext {

  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  override protected def timeouts = new ProcessingTimeout

  import DbActivityRecordMetaStore.ExtraActivityRecordMetaT

  private object Tables {
    val extraActivityRecordMeta = "extra_app_activity_record_meta"
  }

  private def historyId = updateHistory.historyId

  private implicit val getResultExtraActivityRecordMeta: GetResult[ExtraActivityRecordMetaT] =
    GetResult { prs =>
      ExtraActivityRecordMetaT(
        historyId = prs.<<[Long],
        codeVersion = prs.<<[Int],
        userVersion = prs.<<[Int],
        startedIngestingAt = prs.<<[Long],
        earliestIngestedRound = prs.<<[Long],
        lastArchivedRound = prs.<<[Option[Long]],
      )
    }

  // ============ Public combine overrides ============

  override def startedIngestingAt(implicit tc: TraceContext): Future[Option[Long]] =
    appActivityRecordStore.startedIngestingAt
      .zip(extraStartedIngestingAt)
      .map {
        case (Some(a), Some(e)) => Some(math.min(a, e))
        case (someA, None) => someA
        case (None, someE) => someE
      }

  override def earliestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    appActivityRecordStore
      .earliestRoundWithCompleteAppActivity()
      .zip(extraEarliestRoundWithCompleteAppActivity())
      .map {
        case (Some(a), Some(e)) => Some(math.max(a, e))
        case _ => None
      }

  override def latestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    appActivityRecordStore
      .latestRoundWithCompleteAppActivity()
      .zip(extraLatestRoundWithCompleteAppActivity())
      .map {
        case (Some(a), Some(e)) => Some(math.min(a, e))
        case _ => None
      }

  override def ingestionStatusForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[RoundIngestionStatus] =
    appActivityRecordStore
      .ingestionStatusForRound(roundNumber)
      .zip(extraIngestionStatusForRound(roundNumber))
      .map {
        case (RoundIngestionStatus.CannotProvide, _) => RoundIngestionStatus.CannotProvide
        case (_, RoundIngestionStatus.CannotProvide) => RoundIngestionStatus.CannotProvide
        case _ => RoundIngestionStatus.Undetermined
      }

  // ============ Extra-side meta reads (private helpers) ============

  private def extraStartedIngestingAt(implicit tc: TraceContext): Future[Option[Long]] =
    runQuerySingle(
      sql"""select m.started_ingesting_at
            from #${Tables.extraActivityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = ${ingestionVersions.code}
              and m.activity_ingestion_user_version = ${ingestionVersions.user}
      """.as[Long].headOption,
      "extraActivity.startedIngestingAt",
    )

  private def extraEarliestIngestedRound()(implicit tc: TraceContext): Future[Option[Long]] =
    runQuerySingle(
      sql"""select m.earliest_ingested_round
            from #${Tables.extraActivityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = ${ingestionVersions.code}
              and m.activity_ingestion_user_version = ${ingestionVersions.user}
      """.as[Long].headOption,
      "extraActivity.earliestIngestedRound",
    )

  private def extraEarliestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    runQuerySingle(
      sql"""select m.earliest_ingested_round + 1
            from #${Tables.extraActivityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = ${ingestionVersions.code}
              and m.activity_ingestion_user_version = ${ingestionVersions.user}
              and m.last_archived_round >= m.earliest_ingested_round + 1
      """.as[Option[Long]].headOption.map(_.flatten),
      "extraActivity.earliestRoundWithCompleteAppActivity",
    )

  private def extraLatestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    runQuerySingle(
      sql"""select m.last_archived_round
            from #${Tables.extraActivityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = ${ingestionVersions.code}
              and m.activity_ingestion_user_version = ${ingestionVersions.user}
      """.as[Option[Long]].headOption.map(_.flatten),
      "extraActivity.latestRoundWithCompleteAppActivity",
    )

  private def extraIngestionStatusForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[RoundIngestionStatus] =
    extraEarliestIngestedRound().map {
      case Some(earliestIngested) if roundNumber <= earliestIngested =>
        RoundIngestionStatus.CannotProvide
      case Some(_) =>
        RoundIngestionStatus.Undetermined
      case None if !isFirstSv =>
        RoundIngestionStatus.CannotProvide
      case None =>
        RoundIngestionStatus.Undetermined
    }

  // ============ Extra-side meta writes ============

  def lookupExtraActivityRecordMeta(codeVersion: Int, userVersion: Int)(implicit
      tc: TraceContext
  ): Future[Option[ExtraActivityRecordMetaT]] =
    runQuerySingle(
      sql"""select history_id, activity_ingestion_code_version,
                   activity_ingestion_user_version, started_ingesting_at,
                   earliest_ingested_round, last_archived_round
            from #${Tables.extraActivityRecordMeta}
            where history_id = $historyId
              and activity_ingestion_code_version = $codeVersion
              and activity_ingestion_user_version = $userVersion
      """.as[ExtraActivityRecordMetaT].headOption,
      "extraActivity.lookupExtraActivityRecordMeta",
    )

  def insertExtraActivityRecordMetaDBIO(
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  ) =
    sql"""insert into #${Tables.extraActivityRecordMeta}
            (history_id, activity_ingestion_code_version,
             activity_ingestion_user_version, started_ingesting_at,
             earliest_ingested_round, last_archived_round)
          values ($historyId, $codeVersion, $userVersion, $startedIngestingAt,
                  $earliestIngestedRound, $lastArchivedRound)
          ON CONFLICT DO NOTHING
    """.asUpdate

  @VisibleForTesting
  def insertExtraActivityRecordMetaForTesting(
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  )(implicit tc: TraceContext): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.update_(
        insertExtraActivityRecordMetaDBIO(
          codeVersion,
          userVersion,
          startedIngestingAt,
          earliestIngestedRound,
          lastArchivedRound,
        ),
        "extraActivity.insertExtraActivityRecordMeta",
      )
    )

  /** Bootstrap the extra-side meta row with the PoC stub for
    * `earliest_ingested_round`.
    *
    * The CIP-0104 extension-hook design doc derives
    * `earliest_ingested_round` for non-first SVs from
    * `DsoRules.svSwitchOverTimes["cip-104/enable-extra-activity-after"]`.
    * That switchover-time key does not yet exist on splice-side `DsoRules`,
    * so this PoC always uses `initialRound - 1`. Replace with the real
    * rule once the Daml-side key lands.
    */
  def startAndEnsureExtraMeta(startedIngestingAt: Long)(implicit
      tc: TraceContext
  ): Future[Unit] =
    insertExtraActivityRecordMetaForTesting(
      codeVersion = ingestionVersions.code,
      userVersion = ingestionVersions.user,
      startedIngestingAt = startedIngestingAt,
      earliestIngestedRound = initialRound - 1,
      lastArchivedRound = None,
    )

  def updateExtraLastArchivedRoundDBIO(round: Long) =
    sql"""update #${Tables.extraActivityRecordMeta}
          set last_archived_round = $round,
              last_updated_at = now()
          where history_id = $historyId
            and activity_ingestion_code_version = ${ingestionVersions.code}
            and activity_ingestion_user_version = ${ingestionVersions.user}
    """.asUpdate

  @VisibleForTesting
  def bumpExtraLastArchivedRoundForTesting(round: Long)(implicit
      tc: TraceContext
  ): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.update_(
        updateExtraLastArchivedRoundDBIO(round),
        "extraActivity.updateLastArchivedRound",
      )
    )

  private def runQuerySingle[T](
      action: DBIOAction[Option[T], NoStream, Effect.Read],
      operationName: String,
  )(implicit tc: TraceContext): Future[Option[T]] =
    futureUnlessShutdownToFuture(storage.querySingle(action, operationName).value)
}

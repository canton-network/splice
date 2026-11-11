// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import org.lfdecentralizedtrust.splice.scan.store.AppActivityStore.RoundIngestionStatus
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.config.ProcessingTimeout
import com.google.common.annotations.VisibleForTesting
import slick.jdbc.{GetResult, PostgresProfile}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}

import scala.concurrent.{ExecutionContext, Future}

object DbExtraActivityRecordStore {

  /** Extra activity record contributed by an
    * `ActivityRecorder_DsoRecordActivity` exercise (CIP-0104 amendment).
    *
    * `history_id` is injected from the store's `UpdateHistory` on insert
    * and omitted here. The DB PK is `(history_id, round_number,
    * record_time)` — a PoC stand-in for a proper source-event identity.
    * Mirrors the shape of
    * `DbAppActivityRecordStore.AppAndAssetActivityRecordT` modulo the
    * source-side keying.
    */
  final case class ExtraActivityRecordT(
      roundNumber: Long,
      recordTime: Long,
      appProviderParties: Seq[String],
      appActivityWeights: Seq[Long],
      assetActivityProviderParties: Seq[String] = Seq.empty,
      assetActivityWeights: Seq[Long] = Seq.empty,
  )

  /** Metadata row for extra-activity ingestion. Mirrors
    * `DbAppActivityRecordStore.AppActivityRecordMetaT` exactly.
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

/** Scan-side store for the "extra" activity records contributed by the
  * CIP-0104 amendment's `ActivityRecorder_DsoRecordActivity` choice. Rows
  * are derived from update-history exercises, NOT from mediator verdicts —
  * so there is no FK to `scan_verdict_store`. PK is a composite natural
  * key on `(history_id, round_number, record_time)` so that the insert
  * path can `ON CONFLICT DO NOTHING` under Canton's retry-forever write
  * semantics.
  *
  * The query methods (`earliestRoundWithCompleteAppActivity`,
  * `earliestIngestedRound`, `latestRoundWithCompleteAppActivity`,
  * `ingestionStatusForRound`, `startedIngestingAt`) match the
  * `AppActivityStore` trait's shape so `DbActivityRecordMetaStore` can
  * delegate here. This store does not implement the trait itself — the
  * meta-store facade is the sole implementor.
  */
class DbExtraActivityRecordStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
    val ingestionVersions: DbAppActivityRecordStore.IngestionVersions,
    isFirstSv: Boolean,
    initialRound: Long,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends NamedLogging
    with org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
    with org.lfdecentralizedtrust.splice.store.db.AcsQueries
    with FlagCloseable
    with HasCloseContext {

  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  override protected def timeouts = new ProcessingTimeout

  object Tables {
    val extraActivityRecords = "extra_app_activity_record_store"
    val extraActivityRecordMeta = "extra_app_activity_record_meta"
  }

  private def historyId = updateHistory.historyId

  type ExtraActivityRecordT = DbExtraActivityRecordStore.ExtraActivityRecordT
  type ExtraActivityRecordMetaT = DbExtraActivityRecordStore.ExtraActivityRecordMetaT

  private implicit val getResultExtraActivityRecord: GetResult[ExtraActivityRecordT] =
    GetResult { prs =>
      DbExtraActivityRecordStore.ExtraActivityRecordT(
        roundNumber = prs.<<[Long],
        recordTime = prs.<<[Long],
        appProviderParties = stringSeqGetResult(prs),
        appActivityWeights = longSeqGetResult(prs),
        assetActivityProviderParties = stringSeqGetResult(prs),
        assetActivityWeights = longSeqGetResult(prs),
      )
    }

  private implicit val getResultExtraActivityRecordMeta: GetResult[ExtraActivityRecordMetaT] =
    GetResult { prs =>
      DbExtraActivityRecordStore.ExtraActivityRecordMetaT(
        historyId = prs.<<[Long],
        codeVersion = prs.<<[Int],
        userVersion = prs.<<[Int],
        startedIngestingAt = prs.<<[Long],
        earliestIngestedRound = prs.<<[Long],
        lastArchivedRound = prs.<<[Option[Long]],
      )
    }

  // Round-completion query methods — mirror AppActivityStore's surface
  // so the step-3 facade can delegate here without a type-level dance.

  def startedIngestingAt(implicit tc: TraceContext): Future[Option[Long]] =
    runQuerySingle(
      sql"""select m.started_ingesting_at
            from #${Tables.extraActivityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = ${ingestionVersions.code}
              and m.activity_ingestion_user_version = ${ingestionVersions.user}
      """.as[Long].headOption,
      "extraActivity.startedIngestingAt",
    )

  def earliestIngestedRound()(implicit tc: TraceContext): Future[Option[Long]] =
    runQuerySingle(
      sql"""select m.earliest_ingested_round
            from #${Tables.extraActivityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = ${ingestionVersions.code}
              and m.activity_ingestion_user_version = ${ingestionVersions.user}
      """.as[Long].headOption,
      "extraActivity.earliestIngestedRound",
    )

  def earliestRoundWithCompleteAppActivity()(implicit
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

  def latestRoundWithCompleteAppActivity()(implicit
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

  def ingestionStatusForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[RoundIngestionStatus] =
    earliestIngestedRound().map {
      case Some(earliestIngested) if roundNumber <= earliestIngested =>
        RoundIngestionStatus.CannotProvide
      case Some(_) =>
        RoundIngestionStatus.Undetermined
      case None if !isFirstSv =>
        RoundIngestionStatus.CannotProvide
      case None =>
        RoundIngestionStatus.Undetermined
    }

  // Activity-record insert / read paths — minimum PoC surface.

  private def batchInsertExtraActivityRecords(items: Seq[ExtraActivityRecordT]) =
    if (items.isEmpty) {
      DBIO.successful(0)
    } else {
      val values = sqlCommaSeparated(
        items.map { row =>
          sql"""($historyId, ${row.roundNumber}, ${row.recordTime},
                ${row.appProviderParties}, ${row.appActivityWeights},
                ${row.assetActivityProviderParties}, ${row.assetActivityWeights})"""
        }
      )
      (sql"""
        insert into #${Tables.extraActivityRecords}(
          history_id, round_number, record_time,
          app_provider_parties, app_activity_weights,
          asset_activity_provider_parties, asset_activity_weights
        ) values """ ++ values ++ sql" ON CONFLICT DO NOTHING").asUpdate
    }

  @VisibleForTesting
  def insertExtraActivityRecordsForTesting(
      items: Seq[ExtraActivityRecordT]
  )(implicit tc: TraceContext): Future[Unit] =
    if (items.isEmpty) Future.unit
    else
      futureUnlessShutdownToFuture(
        storage.update_(
          batchInsertExtraActivityRecords(items),
          "extraActivity.insertExtraActivityRecords.batch",
        )
      )

  /** Read every extra activity record for this store's `history_id`, ordered
    * by `(round_number, record_time)`. Round-trip helper only.
    */
  @VisibleForTesting
  def getRecordsForTesting()(implicit tc: TraceContext): Future[Seq[ExtraActivityRecordT]] =
    futureUnlessShutdownToFuture(
      storage.query(
        sql"""
          select round_number, record_time,
                 app_provider_parties, app_activity_weights,
                 asset_activity_provider_parties, asset_activity_weights
          from #${Tables.extraActivityRecords}
          where history_id = $historyId
          order by round_number, record_time
        """.as[ExtraActivityRecordT],
        "extraActivity.getRecordsForTesting",
      )
    )

  // Meta-row insert / lookup / update.

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

  /** Bootstrap the meta row with the PoC stub for `earliest_ingested_round`.
    *
    * The CIP-0104 extension-hook design doc derives `earliest_ingested_round`
    * for non-first SVs from
    * `DsoRules.svSwitchOverTimes["cip-104/enable-extra-activity-after"]`.
    * That switchover-time key does not yet exist on splice-side `DsoRules`,
    * so this PoC always uses `initialRound - 1`. Replace with the real
    * rule once the Daml-side key lands.
    */
  def startAndEnsureMeta(startedIngestingAt: Long)(implicit
      tc: TraceContext
  ): Future[Unit] =
    insertExtraActivityRecordMetaForTesting(
      codeVersion = ingestionVersions.code,
      userVersion = ingestionVersions.user,
      startedIngestingAt = startedIngestingAt,
      earliestIngestedRound = initialRound - 1,
      lastArchivedRound = None,
    )

  def updateLastArchivedRoundDBIO(round: Long) =
    sql"""update #${Tables.extraActivityRecordMeta}
          set last_archived_round = $round,
              last_updated_at = now()
          where history_id = $historyId
            and activity_ingestion_code_version = ${ingestionVersions.code}
            and activity_ingestion_user_version = ${ingestionVersions.user}
    """.asUpdate

  @VisibleForTesting
  def bumpLastArchivedRoundForTesting(round: Long)(implicit
      tc: TraceContext
  ): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.update_(
        updateLastArchivedRoundDBIO(round),
        "extraActivity.updateLastArchivedRound",
      )
    )

  private def runQuerySingle[T](
      action: DBIOAction[Option[T], NoStream, Effect.Read],
      operationName: String,
  )(implicit tc: TraceContext): Future[Option[T]] =
    futureUnlessShutdownToFuture(storage.querySingle(action, operationName).value)
}

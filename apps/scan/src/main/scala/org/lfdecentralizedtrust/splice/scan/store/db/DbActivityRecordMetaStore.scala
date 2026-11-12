// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.google.common.annotations.VisibleForTesting
import io.grpc.Status
import org.lfdecentralizedtrust.splice.scan.store.ActivityRecordMetaStore
import org.lfdecentralizedtrust.splice.scan.store.ActivityRecordMetaStore.RoundIngestionStatus
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}
import slick.jdbc.{GetResult, PostgresProfile}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{ExecutionContext, Future}

object DbActivityRecordMetaStore {

  /** Code + user ingestion-version pair. One tuple applies to both activity
    * sides; each side's meta row is keyed by `(history_id, code, user)`.
    */
  final case class IngestionVersions(code: Int, user: Int)

  /** Metadata row for app-side activity ingestion (TBAR), read from and
    * written to `app_activity_record_meta`.
    */
  final case class AppActivityRecordMetaT(
      historyId: Long,
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  )

  /** Metadata row for extra-activity ingestion (CIP-0104 extension hook),
    * read from and written to `extra_app_activity_record_meta`.
    *
    * POC: identical to `AppActivityRecordMetaT` pending the open decision
    * on whether the extra meta table keeps `code_version` / `user_version`.
    */
  final case class ExtraActivityRecordMetaT(
      historyId: Long,
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  )

  sealed trait MetaCheckResult
  case object InsertMeta extends MetaCheckResult
  case object Resume extends MetaCheckResult
  final case class DowngradeDetected(
      runningCode: Int,
      runningUser: Int,
      storedCode: Int,
      storedUser: Int,
  ) extends MetaCheckResult

  def checkMetaVersions(
      existing: Option[(Int, Int)],
      runningCode: Int,
      runningUser: Int,
  ): MetaCheckResult = existing match {
    case None => InsertMeta
    case Some((storedCode, storedUser)) =>
      if (runningCode < storedCode || runningUser < storedUser)
        DowngradeDetected(runningCode, runningUser, storedCode, storedUser)
      else if (runningCode > storedCode || runningUser > storedUser)
        InsertMeta
      else
        Resume
  }
}

/** Implementation of `ActivityRecordMetaStore`, owning the meta surface for
  * both activity-record sides: the TBAR app side (`app_activity_record_meta`,
  * fed from verdict ingestion via `DbAppActivityRecordStore`'s orchestrator)
  * and the CIP-0104 extension hook side (`extra_app_activity_record_meta`,
  * fed from `ActivityRecorder_DsoRecordActivity` exercises). The trait
  * methods return a combined view across the two sides.
  *
  * The two concrete row stores (`DbAppActivityRecordStore`,
  * `DbExtraActivityRecordStore`) do not touch their meta tables; they
  * consume the per-side meta helpers here. Row-level case classes and
  * insert/read paths stay with the concrete stores.
  *
  * Combine semantics:
  *   - `earliestRoundWithCompleteAppActivity`: max of the two sides —
  *     completeness from genesis requires BOTH to have reached the round.
  *     Returns None if either side is None.
  *   - `latestRoundWithCompleteAppActivity`: min of the two — the merged
  *     frontier is bounded by whichever side is slower. Returns None if
  *     either side is None.
  *   - `ingestionStatusForRound`: if either side reports `CannotProvide`,
  *     the result is `CannotProvide` (caller should delegate to BFT).
  *     Otherwise `Undetermined` (either or both sides still catching up).
  *   - `startedIngestingAt`: the earlier of the two start times when both
  *     are available; the single non-None value otherwise.
  */
class DbActivityRecordMetaStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
    val ingestionVersions: DbActivityRecordMetaStore.IngestionVersions,
    isFirstSv: Boolean,
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

  import DbActivityRecordMetaStore.*

  private object Tables {
    val appActivityRecordMeta = "app_activity_record_meta"
    val extraActivityRecordMeta = "extra_app_activity_record_meta"
  }

  private def historyId = updateHistory.historyId

  private implicit val getResultAppActivityRecordMeta: GetResult[AppActivityRecordMetaT] =
    GetResult { prs =>
      AppActivityRecordMetaT(
        historyId = prs.<<[Long],
        codeVersion = prs.<<[Int],
        userVersion = prs.<<[Int],
        startedIngestingAt = prs.<<[Long],
        earliestIngestedRound = prs.<<[Long],
        lastArchivedRound = prs.<<[Option[Long]],
      )
    }

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
    appStartedIngestingAt
      .zip(extraStartedIngestingAt)
      .map {
        case (Some(a), Some(e)) => Some(math.min(a, e))
        case (someA, None) => someA
        case (None, someE) => someE
      }

  override def earliestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    appEarliestRoundWithCompleteAppActivity()
      .zip(extraEarliestRoundWithCompleteAppActivity())
      .map {
        case (Some(a), Some(e)) => Some(math.max(a, e))
        case _ => None
      }

  override def latestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    appLatestRoundWithCompleteAppActivity()
      .zip(extraLatestRoundWithCompleteAppActivity())
      .map {
        case (Some(a), Some(e)) => Some(math.min(a, e))
        case _ => None
      }

  override def ingestionStatusForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[RoundIngestionStatus] =
    appIngestionStatusForRound(roundNumber)
      .zip(extraIngestionStatusForRound(roundNumber))
      .map {
        case (RoundIngestionStatus.CannotProvide, _) => RoundIngestionStatus.CannotProvide
        case (_, RoundIngestionStatus.CannotProvide) => RoundIngestionStatus.CannotProvide
        case _ => RoundIngestionStatus.Undetermined
      }

  // ============ App-side meta ============

  def appStartedIngestingAt(implicit tc: TraceContext): Future[Option[Long]] =
    cachedStartedIngestingAt(
      appStartedIngestingAtRef,
      lookupAppActivityRecordMeta(ingestionVersions.code, ingestionVersions.user)
        .map(_.map(_.startedIngestingAt)),
    )

  def ensureAppMetaDBIO(
      ingestionStart: (Long, Long),
      lastArchivedRoundO: Option[Long] = None,
      exitOnDowngrade: Boolean = true,
  )(implicit tc: TraceContext): DBIO[MetaCheckResult] =
    ensureMetaDBIO(
      Tables.appActivityRecordMeta,
      appMetaChecked,
      insertMetaDBIO(Tables.appActivityRecordMeta),
      "App activity ingestion",
    )(ingestionStart, lastArchivedRoundO, exitOnDowngrade)

  def updateAppLastArchivedRoundDBIO(round: Long) =
    updateLastArchivedRoundDBIO(Tables.appActivityRecordMeta)(round)

  @VisibleForTesting
  def insertAppActivityRecordMetaForTesting(
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  )(implicit tc: TraceContext): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.update_(
        insertMetaDBIO(Tables.appActivityRecordMeta)(
          codeVersion,
          userVersion,
          startedIngestingAt,
          earliestIngestedRound,
          lastArchivedRound,
        ),
        "appActivity.insertAppActivityRecordMeta",
      )
    )

  private[store] def lookupAppActivityRecordMeta(codeVersion: Int, userVersion: Int)(implicit
      tc: TraceContext
  ): Future[Option[AppActivityRecordMetaT]] =
    lookupMeta[AppActivityRecordMetaT](
      Tables.appActivityRecordMeta,
      "appActivity.lookupAppActivityRecordMeta",
    )(codeVersion, userVersion)

  private[store] def appEarliestIngestedRound()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    readMetaOptLong(
      Tables.appActivityRecordMeta,
      "m.earliest_ingested_round",
      "",
      "appActivity.earliestIngestedRound",
    )

  /** Find the earliest round with complete app-side activity.
    * The first ingested round may be partial, so the earliest complete round
    * is `earliest_ingested_round + 1`. Returns the round only after it has
    * been archived.
    */
  private[store] def appEarliestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    readMetaOptLong(
      Tables.appActivityRecordMeta,
      "m.earliest_ingested_round + 1",
      "and m.last_archived_round >= m.earliest_ingested_round + 1",
      "appActivity.earliestRoundWithCompleteAppActivity",
    )

  /** Latest round with complete app-side activity (= `last_archived_round`). */
  private[store] def appLatestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    readMetaOptLong(
      Tables.appActivityRecordMeta,
      "m.last_archived_round",
      "",
      "appActivity.latestRoundWithCompleteAppActivity",
    )

  private[store] def appIngestionStatusForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[RoundIngestionStatus] =
    metaIngestionStatus(appEarliestIngestedRound(), roundNumber)

  /** Assert that all activity records for roundNumber have been ingested
    * on the app side. The round must be greater than earliest_ingested_round
    * and less than or equal to last_archived_round.
    */
  private[store] def appAssertCompleteActivity(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Unit] =
    runQuerySingle(
      sql"""select m.earliest_ingested_round, m.last_archived_round
            from #${Tables.appActivityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = ${ingestionVersions.code}
              and m.activity_ingestion_user_version = ${ingestionVersions.user}
      """.as[(Long, Option[Long])].headOption,
      "appActivity.assertCompleteActivity",
    ).map {
      case Some((earliest, Some(lastArchived)))
          if roundNumber > earliest && roundNumber <= lastArchived =>
        ()
      case metaO =>
        val (earliestO, lastArchivedO) =
          metaO.fold((Option.empty[Long], Option.empty[Long])) { case (e, l) => (Some(e), l) }
        throw Status.FAILED_PRECONDITION
          .withDescription(
            s"Incomplete app activity for round $roundNumber: we require " +
              s"earliest_ingested_round < round <= last_archived_round, but " +
              s"earliest_ingested_round=$earliestO, last_archived_round=$lastArchivedO"
          )
          .asRuntimeException()
    }

  private val appStartedIngestingAtRef = new AtomicReference[Option[Long]](None)
  private val appMetaChecked = new AtomicBoolean(false)

  // ============ Extra-side meta ============

  def ensureExtraMetaDBIO(
      ingestionStart: (Long, Long),
      lastArchivedRoundO: Option[Long] = None,
      exitOnDowngrade: Boolean = true,
  )(implicit tc: TraceContext): DBIO[MetaCheckResult] =
    ensureMetaDBIO(
      Tables.extraActivityRecordMeta,
      extraMetaChecked,
      insertMetaDBIO(Tables.extraActivityRecordMeta),
      "Extra activity ingestion",
    )(ingestionStart, lastArchivedRoundO, exitOnDowngrade)

  /** Intended consumer: `ScanExtraActivityIngestionService`, which
    * advances `last_archived_round` per batch / OffsetCheckpoint.
    */
  def updateExtraLastArchivedRoundDBIO(round: Long) =
    updateLastArchivedRoundDBIO(Tables.extraActivityRecordMeta)(round)

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
        insertMetaDBIO(Tables.extraActivityRecordMeta)(
          codeVersion,
          userVersion,
          startedIngestingAt,
          earliestIngestedRound,
          lastArchivedRound,
        ),
        "extraActivity.insertExtraActivityRecordMeta",
      )
    )

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

  private[store] def lookupExtraActivityRecordMeta(codeVersion: Int, userVersion: Int)(implicit
      tc: TraceContext
  ): Future[Option[ExtraActivityRecordMetaT]] =
    lookupMeta[ExtraActivityRecordMetaT](
      Tables.extraActivityRecordMeta,
      "extraActivity.lookupExtraActivityRecordMeta",
    )(codeVersion, userVersion)

  private def extraStartedIngestingAt(implicit tc: TraceContext): Future[Option[Long]] =
    cachedStartedIngestingAt(
      extraStartedIngestingAtRef,
      lookupExtraActivityRecordMeta(ingestionVersions.code, ingestionVersions.user)
        .map(_.map(_.startedIngestingAt)),
    )

  private def extraEarliestIngestedRound()(implicit tc: TraceContext): Future[Option[Long]] =
    readMetaOptLong(
      Tables.extraActivityRecordMeta,
      "m.earliest_ingested_round",
      "",
      "extraActivity.earliestIngestedRound",
    )

  private def extraEarliestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    readMetaOptLong(
      Tables.extraActivityRecordMeta,
      "m.earliest_ingested_round + 1",
      "and m.last_archived_round >= m.earliest_ingested_round + 1",
      "extraActivity.earliestRoundWithCompleteAppActivity",
    )

  private def extraLatestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    readMetaOptLong(
      Tables.extraActivityRecordMeta,
      "m.last_archived_round",
      "",
      "extraActivity.latestRoundWithCompleteAppActivity",
    )

  private def extraIngestionStatusForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[RoundIngestionStatus] =
    metaIngestionStatus(extraEarliestIngestedRound(), roundNumber)

  private val extraStartedIngestingAtRef = new AtomicReference[Option[Long]](None)
  private val extraMetaChecked = new AtomicBoolean(false)

  // ============ Shared helpers ============

  /** Cache-when-non-None helper for `started_ingesting_at`: once `lookup`
    * observes a Some, the cached value is returned without further lookups
    * (the column is monotonic-set-once).
    */
  private def cachedStartedIngestingAt(
      cache: AtomicReference[Option[Long]],
      lookup: => Future[Option[Long]],
  ): Future[Option[Long]] =
    cache.get() match {
      case some @ Some(_) => Future.successful(some)
      case None =>
        lookup.map { tsO =>
          tsO.foreach(ts => cache.compareAndSet(None, Some(ts)))
          tsO
        }
    }

  /** Select one Long (or NULL) from a meta table, keyed by
    * `(history_id, activity_ingestion_code_version, activity_ingestion_user_version)`.
    * `columnExpr` is spliced into the SELECT (e.g. `m.earliest_ingested_round + 1`);
    * `extraWhere` is spliced after the key conjunctions (empty string if none).
    */
  private def readMetaOptLong(
      table: String,
      columnExpr: String,
      extraWhere: String,
      label: String,
  )(implicit tc: TraceContext): Future[Option[Long]] =
    runQuerySingle(
      sql"""select #$columnExpr
            from #$table m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = ${ingestionVersions.code}
              and m.activity_ingestion_user_version = ${ingestionVersions.user}
              #$extraWhere
      """.as[Option[Long]].headOption.map(_.flatten),
      label,
    )

  /** Shared `ingestionStatusForRound` branch logic for both sides.
    * `earliestF` is the side-specific `earliestIngestedRound()` query.
    */
  private def metaIngestionStatus(
      earliestF: Future[Option[Long]],
      roundNumber: Long,
  ): Future[RoundIngestionStatus] =
    earliestF.map {
      case Some(earliestIngested) if roundNumber <= earliestIngested =>
        // We should have data for this round but no root hash exists:
        // a peer likely does, so delegate.
        RoundIngestionStatus.CannotProvide
      case Some(_) =>
        // Meta row present but round is beyond our ingested boundary —
        // ingestion is still catching up; retry.
        RoundIngestionStatus.Undetermined
      case None if !isFirstSv =>
        // Late-joining Scan with no ingestion boundary of its own —
        // peers do have one, so delegate.
        RoundIngestionStatus.CannotProvide
      case None =>
        // firstSV during initial ingestion (brief startup window before
        // the meta row is inserted) — retry.
        RoundIngestionStatus.Undetermined
    }

  /** Lookup a full meta row, polymorphic in the row type (each side's
    * `GetResult` is in scope).
    */
  private def lookupMeta[Meta: GetResult](
      table: String,
      label: String,
  )(codeVersion: Int, userVersion: Int)(implicit tc: TraceContext): Future[Option[Meta]] =
    runQuerySingle(
      sql"""select history_id, activity_ingestion_code_version,
                   activity_ingestion_user_version, started_ingesting_at,
                   earliest_ingested_round, last_archived_round
            from #$table
            where history_id = $historyId
              and activity_ingestion_code_version = $codeVersion
              and activity_ingestion_user_version = $userVersion
      """.as[Meta].headOption,
      label,
    )

  /** Insert a meta row (version-triple keyed, idempotent via
    * `ON CONFLICT DO NOTHING`).
    */
  private def insertMetaDBIO(table: String)(
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  ) =
    sql"""insert into #$table
            (history_id, activity_ingestion_code_version,
             activity_ingestion_user_version, started_ingesting_at,
             earliest_ingested_round, last_archived_round)
          values ($historyId, $codeVersion, $userVersion, $startedIngestingAt,
                  $earliestIngestedRound, $lastArchivedRound)
          ON CONFLICT DO NOTHING
    """.asUpdate

  /** Update `last_archived_round` on a meta row (key via the version
    * triple).
    */
  private def updateLastArchivedRoundDBIO(table: String)(round: Long) =
    sql"""update #$table
          set last_archived_round = $round,
              last_updated_at = now()
          where history_id = $historyId
            and activity_ingestion_code_version = ${ingestionVersions.code}
            and activity_ingestion_user_version = ${ingestionVersions.user}
    """.asUpdate

  /** Version-check + insert the meta row if the running versions are new;
    * log `Resume` / `InsertMeta` / `DowngradeDetected`; on downgrade,
    * `sys.exit(1)` when `exitOnDowngrade = true`, else return the result.
    * Short-circuits once `metaChecked` observes a successful check.
    */
  private def ensureMetaDBIO(
      table: String,
      metaChecked: AtomicBoolean,
      insert: (Int, Int, Long, Long, Option[Long]) => DBIO[Int],
      logPrefix: String,
  )(
      ingestionStart: (Long, Long),
      lastArchivedRoundO: Option[Long],
      exitOnDowngrade: Boolean,
  )(implicit tc: TraceContext): DBIO[MetaCheckResult] = {
    val codeVersion = ingestionVersions.code
    val userVersion = ingestionVersions.user
    val (firstRecordTimeMicros, earliestRound) = ingestionStart
    if (metaChecked.get()) DBIO.successful(Resume)
    else {
      for {
        maxVersions <- sql"""select max(activity_ingestion_code_version),
                                    max(activity_ingestion_user_version)
                             from #$table
                             where history_id = $historyId
                       """
          .as[(Option[Int], Option[Int])]
          .headOption
          .map(_.flatMap {
            case (Some(c), Some(u)) => Some((c, u))
            case _ => None
          })
        result <- checkMetaVersions(maxVersions, codeVersion, userVersion) match {
          case InsertMeta =>
            insert(
              codeVersion,
              userVersion,
              firstRecordTimeMicros,
              earliestRound,
              lastArchivedRoundO,
            )
              .map { _ =>
                logger.info(
                  s"$logPrefix inserted new meta row for " +
                    s"codeVersion=$codeVersion, userVersion=$userVersion."
                )
                metaChecked.set(true)
                InsertMeta: MetaCheckResult
              }
          case Resume =>
            logger.info(
              s"$logPrefix resumed with existing meta row for " +
                s"codeVersion=$codeVersion, userVersion=$userVersion."
            )
            DBIO.successful {
              metaChecked.set(true)
              Resume: MetaCheckResult
            }
          case d: DowngradeDetected =>
            logger.error(
              s"$logPrefix version downgrade detected: " +
                s"running=(${d.runningCode},${d.runningUser}), stored=(${d.storedCode},${d.storedUser}). " +
                s"Make sure you did not accidentally remove or downgrade the 'activity-ingestion-user-version' field" +
                s"in the scan app config. Shutting down to prevent data corruption."
            )
            if (exitOnDowngrade) {
              sys.exit(1)
            } else {
              DBIO.successful(d: MetaCheckResult)
            }
        }
      } yield result
    }
  }

  private def runQuerySingle[T](
      action: DBIOAction[Option[T], NoStream, Effect.Read],
      operationName: String,
  )(implicit tc: TraceContext): Future[Option[T]] =
    futureUnlessShutdownToFuture(storage.querySingle(action, operationName).value)
}

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

  /** Metadata row for extra-activity ingestion (CIP-0104 extension hook).
    * Owned here because the facade is the sole reader/writer of
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

  /** Metadata row for app-side activity ingestion (TBAR).
    * Owned here as the facade becomes the sole reader/writer of
    * `app_activity_record_meta`; `DbAppActivityRecordStore.AppActivityRecordMetaT`
    * will be deleted in the next sub-step of the fold.
    */
  final case class AppActivityRecordMetaT(
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

/** Facade owning the meta surface for both activity-record sides: the TBAR
  * app side (`app_activity_record_meta`, fed from verdict ingestion via
  * `DbAppActivityRecordStore`'s orchestrator) and the CIP-0104 extension
  * hook side (`extra_app_activity_record_meta`, fed from
  * `ActivityRecorder_DsoRecordActivity` exercises). Implements the
  * `ActivityRecordMetaStore` trait as a combined view over the two sides.
  *
  * The two concrete row stores (`DbAppActivityRecordStore`,
  * `DbExtraActivityRecordStore`) no longer touch their meta tables; they
  * consume the per-side meta helpers on this facade. Row-level case classes
  * and insert/read paths stay with the concrete stores.
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

  // ============ App-side meta reads ============

  private val appStartedIngestingAtRef = new AtomicReference[Option[Long]](None)

  def appStartedIngestingAt(implicit tc: TraceContext): Future[Option[Long]] =
    appStartedIngestingAtRef.get() match {
      case some @ Some(_) => Future.successful(some)
      case None =>
        lookupAppActivityRecordMeta(ingestionVersions.code, ingestionVersions.user).map { metaO =>
          val tsO = metaO.map(_.startedIngestingAt)
          tsO.foreach(ts => appStartedIngestingAtRef.compareAndSet(None, Some(ts)))
          tsO
        }
    }

  private[store] def appEarliestIngestedRound()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    runQuerySingle(
      sql"""select m.earliest_ingested_round
            from #${Tables.appActivityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = ${ingestionVersions.code}
              and m.activity_ingestion_user_version = ${ingestionVersions.user}
      """.as[Long].headOption,
      "appActivity.earliestIngestedRound",
    )

  /** Find the earliest round with complete app-side activity.
    * The first ingested round may be partial, so the earliest complete round
    * is `earliest_ingested_round + 1`. Returns the round only after it has
    * been archived.
    */
  def appEarliestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    runQuerySingle(
      sql"""select m.earliest_ingested_round + 1
            from #${Tables.appActivityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = ${ingestionVersions.code}
              and m.activity_ingestion_user_version = ${ingestionVersions.user}
              and m.last_archived_round >= m.earliest_ingested_round + 1
      """.as[Option[Long]].headOption.map(_.flatten),
      "appActivity.earliestRoundWithCompleteAppActivity",
    )

  /** Latest round with complete app-side activity (= `last_archived_round`). */
  def appLatestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    runQuerySingle(
      sql"""select m.last_archived_round
            from #${Tables.appActivityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = ${ingestionVersions.code}
              and m.activity_ingestion_user_version = ${ingestionVersions.user}
      """.as[Option[Long]].headOption.map(_.flatten),
      "appActivity.latestRoundWithCompleteAppActivity",
    )

  def appIngestionStatusForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[RoundIngestionStatus] =
    appEarliestIngestedRound().map {
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

  /** Assert that all activity records for roundNumber have been ingested
    * on the app side. The round must be greater than earliest_ingested_round
    * and less than or equal to last_archived_round.
    */
  def appAssertCompleteActivity(roundNumber: Long)(implicit
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

  // ============ App-side meta writes ============

  def lookupAppActivityRecordMeta(codeVersion: Int, userVersion: Int)(implicit
      tc: TraceContext
  ): Future[Option[AppActivityRecordMetaT]] =
    runQuerySingle(
      sql"""select history_id, activity_ingestion_code_version,
                   activity_ingestion_user_version, started_ingesting_at,
                   earliest_ingested_round, last_archived_round
            from #${Tables.appActivityRecordMeta}
            where history_id = $historyId
              and activity_ingestion_code_version = $codeVersion
              and activity_ingestion_user_version = $userVersion
      """.as[AppActivityRecordMetaT].headOption,
      "appActivity.lookupAppActivityRecordMeta",
    )

  def insertAppActivityRecordMetaDBIO(
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  ) =
    sql"""insert into #${Tables.appActivityRecordMeta}
            (history_id, activity_ingestion_code_version,
             activity_ingestion_user_version, started_ingesting_at,
             earliest_ingested_round, last_archived_round)
          values ($historyId, $codeVersion, $userVersion, $startedIngestingAt,
                  $earliestIngestedRound, $lastArchivedRound)
          ON CONFLICT DO NOTHING
    """.asUpdate

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
        insertAppActivityRecordMetaDBIO(
          codeVersion,
          userVersion,
          startedIngestingAt,
          earliestIngestedRound,
          lastArchivedRound,
        ),
        "appActivity.insertAppActivityRecordMeta",
      )
    )

  def updateAppLastArchivedRoundDBIO(round: Long) =
    sql"""update #${Tables.appActivityRecordMeta}
          set last_archived_round = $round,
              last_updated_at = now()
          where history_id = $historyId
            and activity_ingestion_code_version = ${ingestionVersions.code}
            and activity_ingestion_user_version = ${ingestionVersions.user}
    """.asUpdate

  private val appMetaChecked = new AtomicBoolean(false)

  /** DBIO action that checks/inserts the app-side meta row.
    *
    * @param ingestionStart `(firstRecordTimeMicros, earliestRound)` for the
    *                       batch that triggers meta creation.
    * @param lastArchivedRoundO last archived round to store when inserting
    *                           a new meta row.
    */
  def ensureAppMetaDBIO(
      ingestionStart: (Long, Long),
      lastArchivedRoundO: Option[Long] = None,
      exitOnDowngrade: Boolean = true,
  )(implicit tc: TraceContext): DBIO[MetaCheckResult] = {
    val codeVersion = ingestionVersions.code
    val userVersion = ingestionVersions.user
    val (firstRecordTimeMicros, earliestRound) = ingestionStart
    if (appMetaChecked.get()) DBIO.successful(Resume)
    else {
      for {
        maxVersions <- sql"""select max(activity_ingestion_code_version),
                                    max(activity_ingestion_user_version)
                             from #${Tables.appActivityRecordMeta}
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
            insertAppActivityRecordMetaDBIO(
              codeVersion,
              userVersion,
              firstRecordTimeMicros,
              earliestRound,
              lastArchivedRoundO,
            ).map { _ =>
              logger.info(
                s"App activity ingestion inserted new meta row for " +
                  s"codeVersion=$codeVersion, userVersion=$userVersion."
              )
              appMetaChecked.set(true)
              InsertMeta: MetaCheckResult
            }
          case Resume =>
            logger.info(
              s"App activity ingestion resumed with existing meta row for " +
                s"codeVersion=$codeVersion, userVersion=$userVersion."
            )
            DBIO.successful {
              appMetaChecked.set(true)
              Resume: MetaCheckResult
            }
          case d: DowngradeDetected =>
            logger.error(
              s"App activity ingestion version downgrade detected: " +
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

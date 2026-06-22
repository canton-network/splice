// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import org.lfdecentralizedtrust.splice.scan.store.AppActivityStore
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.config.ProcessingTimeout
import com.google.common.annotations.VisibleForTesting
import io.grpc.Status
import slick.jdbc.{GetResult, PostgresProfile}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{ExecutionContext, Future}

object DbAppActivityRecordStore {

  /** App activity record for a given verdict.
    *
    * @param verdictRowId the row_id of the parent verdict in scan_verdict_store
    * @param roundNumber the mining round that was open at this record_time
    * @param appProviderParties app providers for which app activity should be recorded
    * @param appActivityWeights activity weight (in bytes of traffic) for each app provider,
    *                           in one-to-one correspondence with appProviderParties
    */
  final case class AppActivityRecordT(
      verdictRowId: Long,
      roundNumber: Long,
      appProviderParties: Seq[String],
      appActivityWeights: Seq[Long],
  )

  val DUMMY_VERDICT_ROW_ID: Long = -123456789L

  /** Metadata for an activity record ingestion run.
    *
    * @param historyId history identifier from update_history_descriptors
    * @param codeVersion code version of the ingestion logic
    * @param userVersion operator-configured version from ScanAppConfig
    * @param startedIngestingAt record time (microseconds since epoch) of the first
    *                           verdict in the first batch with activity records
    * @param earliestIngestedRound the earliest round number in the first batch with activity records
    * @param lastArchivedRound the highest round number which has been archived as of
    *                          the max record_time of the ingested verdicts
    */
  final case class AppActivityRecordMetaT(
      historyId: Long,
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  )

  final case class IngestionVersions(code: Int, user: Int)

  sealed trait EnsureResult
  case class Checked(result: MetaCheckResult) extends EnsureResult
  case object NotReady extends EnsureResult

  sealed trait MetaCheckResult
  case object InsertMeta extends MetaCheckResult
  case object Resume extends MetaCheckResult
  final case class DowngradeDetected(
      runningCode: Int,
      runningUser: Int,
      storedCode: Int,
      storedUser: Int,
  ) extends MetaCheckResult {
    def message: String =
      s"Activity ingestion version downgrade detected: " +
        s"running=($runningCode,$runningUser), stored=($storedCode,$storedUser)."
  }

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

class DbAppActivityRecordStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
    val ingestionVersions: DbAppActivityRecordStore.IngestionVersions,
    isFirstSv: Boolean,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends AppActivityStore
    with NamedLogging
    with org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
    with FlagCloseable
    with HasCloseContext
    with org.lfdecentralizedtrust.splice.store.db.AcsQueries { self =>

  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  override protected def timeouts = new ProcessingTimeout

  import DbAppActivityRecordStore.*

  private val startedIngestingAtRef =
    new AtomicReference[Option[Long]](None)

  /** The record time of the first activity record in the store. */
  def startedIngestingAt(implicit tc: TraceContext): Future[Option[Long]] =
    startedIngestingAtRef.get() match {
      case some @ Some(_) => Future.successful(some)
      case None =>
        lookupActivityRecordMeta(ingestionVersions.code, ingestionVersions.user).map { metaO =>
          val tsO = metaO.map(_.startedIngestingAt)
          tsO.foreach(ts => startedIngestingAtRef.compareAndSet(None, Some(ts)))
          tsO
        }
    }

  object Tables {
    val appActivityRecords = "app_activity_record_store"
    val activityRecordMeta = "app_activity_record_meta"
  }

  private def historyId = updateHistory.historyId

  type AppActivityRecordT = DbAppActivityRecordStore.AppActivityRecordT

  private implicit val getResultAppActivityRecord: GetResult[AppActivityRecordT] = GetResult {
    prs =>
      DbAppActivityRecordStore.AppActivityRecordT(
        verdictRowId = prs.<<[Long],
        roundNumber = prs.<<[Long],
        appProviderParties = stringArrayGetResult(prs).toSeq,
        appActivityWeights = longArrayGetResult(prs).toSeq,
      )
  }

  /** Find the earliest round with complete app activity.
    * The first ingested round may be partial, so the earliest complete round
    * is `earliest_ingested_round + 1`.
    * Returns None if no meta row exists or if archival of the earliest round has not happened yet.
    */
  def earliestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {

    val codeVersion = ingestionVersions.code
    val userVersion = ingestionVersions.user
    runQuerySingle(
      sql"""select m.earliest_ingested_round + 1
            from #${Tables.activityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = $codeVersion
              and m.activity_ingestion_user_version = $userVersion
              and m.last_archived_round >= m.earliest_ingested_round + 1
      """.as[Option[Long]].headOption.map(_.flatten),
      "appActivity.earliestRoundWithCompleteAppActivity",
    )
  }

  /** Find the latest round with complete app activity.
    * A round is complete once the verdict ingestion has moved passed its archival.
    * Returns None if no meta row exists or archival of a round has not happened yet.
    */
  def latestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {
    val codeVersion = ingestionVersions.code
    val userVersion = ingestionVersions.user
    runQuerySingle(
      sql"""select m.last_archived_round
            from #${Tables.activityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = $codeVersion
              and m.activity_ingestion_user_version = $userVersion
      """.as[Option[Long]].headOption.map(_.flatten),
      "appActivity.latestRoundWithCompleteAppActivity",
    )
  }

  /** Assert that all activity records for roundNumber have been ingested.
    * The round must be greater than the earliest_ingested_round,
    * and less than or equal to last_archived_round.
    */
  def assertCompleteActivity(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val codeVersion = ingestionVersions.code
    val userVersion = ingestionVersions.user
    runQuerySingle(
      sql"""select m.earliest_ingested_round, m.last_archived_round
            from #${Tables.activityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = $codeVersion
              and m.activity_ingestion_user_version = $userVersion
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
  }

  @VisibleForTesting
  def getRecordByVerdictRowId(verdictRowId: Long)(implicit
      tc: TraceContext
  ): Future[Option[AppActivityRecordT]] = {
    runQuerySingle(
      sql"""
        select verdict_row_id, round_number, app_provider_parties, app_activity_weights
        from #${Tables.appActivityRecords}
        where history_id = $historyId and verdict_row_id = $verdictRowId
        limit 1
      """.as[AppActivityRecordT].headOption,
      "appActivity.getRecordByVerdictRowId",
    )
  }

  def getRecordsByVerdictRowIds(
      verdictRowIds: Seq[Long]
  )(implicit tc: TraceContext): Future[Map[Long, AppActivityRecordT]] = {
    if (verdictRowIds.isEmpty) Future.successful(Map.empty)
    else {
      startedIngestingAt.flatMap {
        case None => Future.successful(Map.empty)
        case Some(_) =>
          storage
            .query(
              (sql"""
              select verdict_row_id, round_number, app_provider_parties, app_activity_weights
              from #${Tables.appActivityRecords}
              where history_id = $historyId and """ ++ inClause("verdict_row_id", verdictRowIds))
                .as[AppActivityRecordT],
              "appActivity.getRecordsByVerdictRowIds",
            )
            .map(rows => rows.map(r => r.verdictRowId -> r).toMap)
      }
    }
  }

  /** Batch insert app activity records using multi-row INSERT. */
  private def batchInsertAppActivityRecords(items: Seq[AppActivityRecordT]) = {
    if (items.isEmpty) {
      DBIO.successful(0)
    } else {
      val values = sqlCommaSeparated(
        items.map { row =>
          sql"""($historyId, ${row.verdictRowId},
                ${row.roundNumber}, ${row.appProviderParties}, ${row.appActivityWeights})"""
        }
      )

      (sql"""
        insert into #${Tables.appActivityRecords}(
          history_id, verdict_row_id, round_number, app_provider_parties, app_activity_weights
        ) values """ ++ values).asUpdate
    }
  }

  /** DBIO action that inserts app activity records and ensures the meta row.
    *
    * @param items activity records to insert
    * @param firstRecordTimeMicros record time of the first verdict in the batch,
    *                              or `None` to skip the meta check
    * @param lastArchivedRound the highest round number which has been archived as of
    *                          the max record_time of the ingested verdicts
    */
  def insertAppActivityRecordsDBIO(
      items: Seq[AppActivityRecordT],
      firstRecordTimeMicros: Option[Long] = None,
      lastArchivedRoundO: Option[Long] = None,
  )(implicit tc: TraceContext): DBIO[Unit] = {
    val ingestionStart = firstRecordTimeMicros.flatMap { ts =>
      if (items.nonEmpty) {
        val earliestRound = items
          .map(_.roundNumber)
          .foldLeft(Long.MaxValue)(math.min)
        Some((ts, earliestRound))
      } else None
    }
    for {
      _ <-
        if (items.isEmpty) DBIO.successful(())
        else
          batchInsertAppActivityRecords(items).map { _ =>
            logger.info(s"Inserted ${items.size} app activity records.")
          }
      ensureResult <- ensureMetaDBIO(ingestionStart, lastArchivedRoundO)
      _ <- (ensureResult, lastArchivedRoundO) match {
        // We already have meta row, so do the update in place.
        case (Checked(Resume), Some(round)) => updateLastArchivedRoundDBIO(round)
        case _ => DBIO.successful(0)
      }
    } yield ensureResult match {
      case Checked(d: DowngradeDetected) =>
        logger.error(s"${d.message} Shutting down to prevent data corruption.")
        sys.exit(1)
      case _ => ()
    }
  }

  /** Insert multiple app activity records in a single transaction.
    */
  @VisibleForTesting
  def insertAppActivityRecords(
      items: Seq[AppActivityRecordT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods

    if (items.isEmpty) Future.unit
    else {
      futureUnlessShutdownToFuture(
        storage
          .queryAndUpdate(
            insertAppActivityRecordsDBIO(items).transactionally,
            "appActivity.insertAppActivityRecords.batch",
          )
      )
    }
  }

  type AppActivityRecordMetaT = DbAppActivityRecordStore.AppActivityRecordMetaT

  private implicit val getResultAppActivityRecordMeta: GetResult[AppActivityRecordMetaT] =
    GetResult { prs =>
      DbAppActivityRecordStore.AppActivityRecordMetaT(
        historyId = prs.<<[Long],
        codeVersion = prs.<<[Int],
        userVersion = prs.<<[Int],
        startedIngestingAt = prs.<<[Long],
        earliestIngestedRound = prs.<<[Long],
        lastArchivedRound = prs.<<[Option[Long]],
      )
    }

  /** Returns the meta row for the given code and user version. */
  def lookupActivityRecordMeta(codeVersion: Int, userVersion: Int)(implicit
      tc: TraceContext
  ): Future[Option[AppActivityRecordMetaT]] =
    runQuerySingle(
      sql"""select history_id, activity_ingestion_code_version,
                   activity_ingestion_user_version, started_ingesting_at,
                   earliest_ingested_round, last_archived_round
            from #${Tables.activityRecordMeta}
            where history_id = $historyId
              and activity_ingestion_code_version = $codeVersion
              and activity_ingestion_user_version = $userVersion
      """.as[AppActivityRecordMetaT].headOption,
      "appActivity.lookupActivityRecordMeta",
    )

  def insertActivityRecordMetaDBIO(
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  ) =
    sql"""insert into #${Tables.activityRecordMeta}
            (history_id, activity_ingestion_code_version,
             activity_ingestion_user_version, started_ingesting_at,
             earliest_ingested_round, last_archived_round)
          values ($historyId, $codeVersion, $userVersion, $startedIngestingAt,
                  $earliestIngestedRound, $lastArchivedRound)
    """.asUpdate

  private def updateLastArchivedRoundDBIO(round: Long) =
    sql"""update #${Tables.activityRecordMeta}
          set last_archived_round = $round,
              last_updated_at = now()
          where history_id = $historyId
            and activity_ingestion_code_version = ${ingestionVersions.code}
            and activity_ingestion_user_version = ${ingestionVersions.user}
    """.asUpdate

  @VisibleForTesting
  def insertActivityRecordMeta(
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  )(implicit tc: TraceContext): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.update_(
        insertActivityRecordMetaDBIO(
          codeVersion,
          userVersion,
          startedIngestingAt,
          earliestIngestedRound,
          lastArchivedRound,
        ),
        "appActivity.insertActivityRecordMeta",
      )
    )

  private val metaChecked = new AtomicBoolean(false)

  /** DBIO action that checks/inserts the meta row.
    *
    * @param ingestionStart `Some((firstRecordTimeMicros, earliestRound))` when
    *                       the batch has activity records, `None` otherwise.
    * @param lastArchivedRoundO the last archived round to store when inserting
    *                           a new meta row
    */
  def ensureMetaDBIO(
      ingestionStart: Option[(Long, Long)],
      lastArchivedRoundO: Option[Long] = None,
  ): DBIO[EnsureResult] = {
    val codeVersion = ingestionVersions.code
    val userVersion = ingestionVersions.user
    if (metaChecked.get()) DBIO.successful(Checked(Resume))
    else {
      for {
        maxVersions <- sql"""select max(activity_ingestion_code_version),
                                    max(activity_ingestion_user_version)
                             from #${Tables.activityRecordMeta}
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
            ingestionStart match {
              case None =>
                DBIO.successful(NotReady: EnsureResult)
              case Some((firstRecordTimeMicros, earliestRound)) =>
                // First SV on fresh network: set earliest_ingested_round to -1
                // so that round 0 passes assertCompleteActivity (0 > -1)
                // and earliestRoundWithCompleteAppActivity returns -1 + 1 = 0.
                val adjustedEarliestRound =
                  if (isFirstSv && maxVersions.isEmpty) -1L else earliestRound
                insertActivityRecordMetaDBIO(
                  codeVersion,
                  userVersion,
                  firstRecordTimeMicros,
                  adjustedEarliestRound,
                  lastArchivedRoundO,
                ).map { _ =>
                  metaChecked.set(true)
                  Checked(InsertMeta): EnsureResult
                }
            }
          case Resume =>
            DBIO.successful {
              metaChecked.set(true)
              Checked(Resume): EnsureResult
            }
          case d: DowngradeDetected =>
            DBIO.successful(Checked(d): EnsureResult)
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

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

  /** Bump this when the functional behavior of the app activity computation changes. */
  val ActivityIngestionCodeVersion: Int = 1

  /** Metadata for an activity record ingestion run.
    *
    * @param historyId history identifier from update_history_descriptors
    * @param codeVersion code version of the ingestion logic
    * @param userVersion operator-configured version from ScanAppConfig
    * @param startedIngestingAt record time (microseconds since epoch) of the first
    *                           verdict in the first batch with activity records
    * @param earliestIngestedRound the earliest round number in the first batch with activity records
    */
  final case class AppActivityRecordMetaT(
      historyId: Long,
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
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
        s"running=($runningCode,$runningUser), stored=($storedCode,$storedUser). " +
        s"Shutting down to prevent data corruption."
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

  // Cached by ensureMetaDBIO and startedIngestingAt.
  private val startedIngestingAtRef =
    new AtomicReference[Option[Long]](None)

  /** The completeness boundary (microseconds since epoch), if known.
    * Backed by DB, cached after first successful check.
    */
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
    *
    * Uses `earliest_ingested_round` from the meta table — the first round
    * may be partial, so the earliest complete round is
    * `earliest_ingested_round + 1`.
    *
    * Returns None if no meta record exists (ingestion hasn't started) or
    * if the next round after the earliest hasn't been ingested yet.
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
              and exists (
                select 1
                from #${Tables.appActivityRecords} a
                where a.history_id = $historyId
                  and a.round_number = m.earliest_ingested_round + 1
              )
      """.as[Option[Long]].headOption.map(_.flatten),
      "appActivity.earliestRoundWithCompleteAppActivity",
    )
  }

  /** Find the latest round with complete app activity.
    * A round is complete when ingestion has moved past it, i.e., the next
    * round also has records. We return max_round - 1 because max_round
    * itself may still be receiving records.
    * Returns None if fewer than two consecutive rounds have been ingested.
    *
    * Unlike [[earliestRoundWithCompleteAppActivity]], this does not filter
    * by ingestion version. The completeness boundary only constrains the
    * start of ingestion — callers should pair this with
    * `earliestRoundWithCompleteAppActivity` to get a valid range.
    */
  def latestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {

    // `order by ... limit 1` is used instead of min/max to force the query planner
    // to use the (history_id, round_number) index.
    runQuerySingle(
      sql"""select max_round - 1
            from (
              select a.round_number as max_round
              from #${Tables.appActivityRecords} a
              where a.history_id = $historyId
              order by a.round_number desc
              limit 1
            ) sub
            where exists (
              select 1
              from #${Tables.appActivityRecords} a
              where a.history_id = $historyId
              and a.round_number = sub.max_round - 1
              order by a.round_number desc
              limit 1
            )
      """.as[Option[Long]].headOption.map(_.flatten),
      "appActivity.latestRoundWithCompleteAppActivity",
    )
  }

  /** Assert that activity records exist for rounds roundNumber - 1 and
    * roundNumber + 1, proving ingestion completeness for roundNumber.
    * Round N-1 proves ingestion was running before N; round N+1 proves
    * ingestion has moved past N, so all of N's records have been ingested.
    */
  def assertCompleteActivity(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.queryAndUpdate(
        for {
          hasPrev <- sql"""select exists(
                             select 1 from #${Tables.appActivityRecords} a
                             where a.history_id = $historyId
                               and a.round_number = ${roundNumber - 1}
                           )""".as[Boolean].head
          hasNext <- sql"""select exists(
                             select 1 from #${Tables.appActivityRecords} a
                             where a.history_id = $historyId
                               and a.round_number = ${roundNumber + 1}
                           )""".as[Boolean].head
          _ = if (!hasPrev || !hasNext)
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"Incomplete app activity for round $roundNumber: " +
                  s"round ${roundNumber - 1} exists=$hasPrev, round ${roundNumber + 1} exists=$hasNext"
              )
              .asRuntimeException()
        } yield (),
        "appActivity.assertCompleteActivity",
      )
    )

  def getRecordByVerdictRowId(verdictRowId: Long)(implicit
      tc: TraceContext
  ): Future[Option[AppActivityRecordT]] = {
    runQuerySingle(
      sql"""
        select verdict_row_id, round_number, app_provider_parties, app_activity_weights
        from #${Tables.appActivityRecords}
        where verdict_row_id = $verdictRowId
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
      storage
        .query(
          (sql"""
          select verdict_row_id, round_number, app_provider_parties, app_activity_weights
          from #${Tables.appActivityRecords}
          where """ ++ inClause("verdict_row_id", verdictRowIds))
            .as[AppActivityRecordT],
          "appActivity.getRecordsByVerdictRowIds",
        )
        .map(rows => rows.map(r => r.verdictRowId -> r).toMap)
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

  /** Returns a DBIO action for inserting app activity records (for use in combined transactions).
    * Unlike insertAppActivityRecords, this doesn't wrap in a transaction or Future.
    */
  def insertAppActivityRecordsDBIO(
      items: Seq[AppActivityRecordT]
  )(implicit tc: TraceContext): DBIO[Unit] = {
    if (items.isEmpty) DBIO.successful(())
    else {
      batchInsertAppActivityRecords(items).map { _ =>
        logger.info(s"Inserted ${items.size} app activity records.")
      }
    }
  }

  /** Insert multiple app activity records in a single transaction.
    *
    * The unique constraint on verdict_row_id (PK) serves as a safety net against duplicates.
    */
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
      )
    }

  /** Returns the meta row for the given code and user version. */
  def lookupActivityRecordMeta(codeVersion: Int, userVersion: Int)(implicit
      tc: TraceContext
  ): Future[Option[AppActivityRecordMetaT]] =
    runQuerySingle(
      sql"""select history_id, activity_ingestion_code_version,
                   activity_ingestion_user_version, started_ingesting_at,
                   earliest_ingested_round
            from #${Tables.activityRecordMeta}
            where history_id = $historyId
              and activity_ingestion_code_version = $codeVersion
              and activity_ingestion_user_version = $userVersion
      """.as[AppActivityRecordMetaT].headOption,
      "appActivity.lookupActivityRecordMeta",
    )

  def insertActivityRecordMeta(
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
  )(implicit tc: TraceContext): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.update_(
        sql"""insert into #${Tables.activityRecordMeta}
                (history_id, activity_ingestion_code_version,
                 activity_ingestion_user_version, started_ingesting_at,
                 earliest_ingested_round)
              values ($historyId, $codeVersion, $userVersion, $startedIngestingAt,
                      $earliestIngestedRound)
        """.asUpdate,
        "appActivity.insertActivityRecordMeta",
      )
    )

  import DbAppActivityRecordStore.*

  private val metaChecked = new AtomicBoolean(false)

  /** DBIO action that checks/inserts the meta row.
    * Composable in a transaction with activity record inserts.
    *
    * @param ingestionStart `Some((firstRecordTimeMicros, earliestRound))` when
    *                       the batch has activity records, `None` otherwise.
    */
  def ensureMetaDBIO(
      ingestionStart: Option[(Long, Long)]
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
                sql"""insert into #${Tables.activityRecordMeta}
                        (history_id, activity_ingestion_code_version,
                         activity_ingestion_user_version, started_ingesting_at,
                         earliest_ingested_round)
                      values ($historyId, $codeVersion, $userVersion,
                              $firstRecordTimeMicros, $earliestRound)
                """.asUpdate.map { _ =>
                  startedIngestingAtRef.set(Some(firstRecordTimeMicros))
                  metaChecked.set(true)
                  Checked(InsertMeta): EnsureResult
                }
            }
          case Resume =>
            sql"""select started_ingesting_at
                  from #${Tables.activityRecordMeta}
                  where history_id = $historyId
                    and activity_ingestion_code_version = $codeVersion
                    and activity_ingestion_user_version = $userVersion
               """.as[Long].headOption.map { tsO =>
              tsO.foreach(ts => startedIngestingAtRef.compareAndSet(None, Some(ts)))
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

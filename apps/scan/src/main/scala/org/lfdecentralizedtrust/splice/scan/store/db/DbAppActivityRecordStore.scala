// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.nonempty.NonEmpty
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
import slick.dbio.DBIO

import scala.concurrent.{ExecutionContext, Future}

object DbAppActivityRecordStore {

  /** App activity record for a given verdict.
    *
    * @param verdictRowId the row_id of the parent verdict in scan_verdict_store
    * @param roundNumber the mining round that was open at this record_time
    * @param appProviderParties app providers for which app activity should be recorded
    * @param appActivityWeights activity weight (in bytes of traffic) for each app provider,
    *                           in one-to-one correspondence with appProviderParties
    * @param submittingAppProvider the featured app that submitted the transaction, if any
    * @param assetActivityProviderParties featured asset providers for which asset activity should be recorded
    * @param assetActivityWeights asset activity weight (in bytes of traffic) for each asset provider,
    *                             in one-to-one correspondence with assetActivityProviderParties
    */
  final case class AppAndAssetActivityRecordT(
      verdictRowId: Long,
      roundNumber: Long,
      appProviderParties: Seq[String],
      appActivityWeights: Seq[Long],
      submittingAppProvider: Option[String] = None,
      assetActivityProviderParties: Seq[String] = Seq.empty,
      assetActivityWeights: Seq[Long] = Seq.empty,
  )

  val DUMMY_VERDICT_ROW_ID: Long = -123456789L
}

/** Row store for the TBAR app-side activity records, backed by
  * `app_activity_record_store`. Rows arrive via verdict ingestion
  * (`DbScanVerdictStore`) which drives the orchestrator
  * `insertAppActivityRecordsDBIO` below: it writes rows atomically with
  * the meta-row bookkeeping, delegating the meta DBIOs to
  * `DbActivityRecordMetaStore` (the sole owner of
  * `app_activity_record_meta`).
  */
class DbAppActivityRecordStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
    metaStore: DbActivityRecordMetaStore,
    isFirstSv: Boolean,
    initialRound: Long,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends NamedLogging
    with org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
    with FlagCloseable
    with HasCloseContext
    with org.lfdecentralizedtrust.splice.store.db.AcsQueries {

  private implicit val dbProfile: DbStorage.Profile = storage.profile
  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  override protected def timeouts = new ProcessingTimeout

  object Tables {
    val appActivityRecords = "app_activity_record_store"
  }

  private def historyId = updateHistory.historyId

  type AppAndAssetActivityRecordT = DbAppActivityRecordStore.AppAndAssetActivityRecordT

  private implicit val getResultAppActivityRecord: GetResult[AppAndAssetActivityRecordT] =
    GetResult { prs =>
      DbAppActivityRecordStore.AppAndAssetActivityRecordT(
        verdictRowId = prs.<<[Long],
        roundNumber = prs.<<[Long],
        appProviderParties = stringArrayGetResult(prs).toSeq,
        appActivityWeights = longArrayGetResult(prs).toSeq,
        submittingAppProvider = prs.<<[Option[String]],
        assetActivityProviderParties = stringSeqGetResult(prs),
        assetActivityWeights = longSeqGetResult(prs),
      )
    }

  @VisibleForTesting
  def getRecordByVerdictRowIdForTesting(verdictRowId: Long)(implicit
      tc: TraceContext
  ): Future[Option[AppAndAssetActivityRecordT]] =
    futureUnlessShutdownToFuture(
      storage
        .querySingle(
          sql"""
          select verdict_row_id, round_number, app_provider_parties, app_activity_weights,
                 submitting_app_provider, asset_activity_provider_parties, asset_activity_weights
          from #${Tables.appActivityRecords}
          where history_id = $historyId and verdict_row_id = $verdictRowId
          limit 1
        """.as[AppAndAssetActivityRecordT].headOption,
          "appActivity.getRecordByVerdictRowIdForTesting",
        )
        .value
    )

  def getRecordsByVerdictRowIds(
      verdictRowIds: Seq[Long]
  )(implicit tc: TraceContext): Future[Map[Long, AppAndAssetActivityRecordT]] =
    NonEmpty.from(verdictRowIds) match {
      case None => Future.successful(Map.empty)
      case Some(verdictRowIds) =>
        metaStore.appStartedIngestingAt.flatMap {
          case None => Future.successful(Map.empty)
          case Some(_) =>
            storage
              .query(
                (sql"""
                select verdict_row_id, round_number, app_provider_parties, app_activity_weights,
                       submitting_app_provider, asset_activity_provider_parties, asset_activity_weights
                from #${Tables.appActivityRecords}
                where history_id = $historyId and """ ++ DbStorage
                  .toInClause("verdict_row_id", verdictRowIds))
                  .as[AppAndAssetActivityRecordT],
                "appActivity.getRecordsByVerdictRowIds",
              )
              .map(rows => rows.map(r => r.verdictRowId -> r).toMap)
        }
    }

  /** Batch insert app activity records using multi-row INSERT. */
  private[db] def batchInsertAppActivityRecords(items: Seq[AppAndAssetActivityRecordT]) =
    if (items.isEmpty) {
      DBIO.successful(0)
    } else {
      val values = sqlCommaSeparated(
        items.map { row =>
          sql"""($historyId, ${row.verdictRowId},
                ${row.roundNumber}, ${row.appProviderParties}, ${row.appActivityWeights},
                ${row.submittingAppProvider},
                ${row.assetActivityProviderParties}, ${row.assetActivityWeights})"""
        }
      )

      (sql"""
        insert into #${Tables.appActivityRecords}(
          history_id, verdict_row_id, round_number, app_provider_parties, app_activity_weights,
          submitting_app_provider, asset_activity_provider_parties, asset_activity_weights
        ) values """ ++ values ++ sql" ON CONFLICT DO NOTHING").asUpdate
    }

  /** Insert activity records and ensure the meta row exists.
    * Creates the meta row when enough information is available to
    * determine which rounds have complete activity, even when no
    * activity records exist (e.g., no featured app providers),
    * but only if traffic-summaries could be obtained for this batch.
    * On a fresh firstSV with no archived rounds, bootstraps round 0
    * as complete.
    */
  def insertAppActivityRecordsDBIO(
      items: Seq[AppAndAssetActivityRecordT],
      firstRecordTimeMicros: Long,
      hasTrafficSummaries: Boolean,
      firstActiveRoundO: Option[Long] = None,
      lastArchivedRoundO: Option[Long] = None,
  )(implicit tc: TraceContext): DBIO[Unit] = {
    val insertRecords =
      if (items.isEmpty) DBIO.successful(())
      else
        batchInsertAppActivityRecords(items).map { _ =>
          logger.info(s"Inserted ${items.size} app activity records.")
        }

    // earliestRound: the oldest round open at the earliest record_time of this batch.
    // or (initialRound - 1) on firstSV, so earliestRoundWithCompleteAppActivity()
    // returns initialRound (correct for non-zero-round bootstrap).
    val earliestRound = if (isFirstSv) Some(initialRound - 1) else firstActiveRoundO

    // lastArchived: the highest round archived as of this verdict batch.
    //   - From the caller when available
    //   - Bootstrapped to initialRound on a fresh firstSV so the
    //     complete-activity window covers the first TBAR round
    val lastArchived = lastArchivedRoundO
      .orElse(if (isFirstSv) Some(initialRound) else None)

    for {
      _ <- insertRecords
      ensureResult <- earliestRound match {
        case Some(earliest) if hasTrafficSummaries =>
          metaStore.ensureAppMetaDBIO((firstRecordTimeMicros, earliest), lastArchived)
        case _ =>
          // Either we have no rounds info and this is not firstSV,
          // or we have not started obtaining the traffic summaries yet
          // — skip meta creation.
          // A later verdict batch will create it.
          DBIO.successful(
            DbActivityRecordMetaStore.Resume: DbActivityRecordMetaStore.MetaCheckResult
          )
      }
      _ <- (ensureResult, lastArchivedRoundO) match {
        // We already have meta row, so do the update in place.
        case (DbActivityRecordMetaStore.Resume, Some(round)) =>
          metaStore.updateAppLastArchivedRoundDBIO(round)
        case _ => DBIO.successful(0)
      }
    } yield ()
  }

  /** Insert activity records only, without meta row management.
    * Tests manage meta rows separately via the facade's
    * `insertAppActivityRecordMetaForTesting`.
    */
  @VisibleForTesting
  def insertAppActivityRecordsForTesting(
      items: Seq[AppAndAssetActivityRecordT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods

    if (items.isEmpty) Future.unit
    else {
      futureUnlessShutdownToFuture(
        storage
          .queryAndUpdate(
            batchInsertAppActivityRecords(items).map(_ => ()).transactionally,
            "appActivity.insertAppActivityRecords.batch",
          )
      )
    }
  }
}

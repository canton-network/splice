// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

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
}

/** Row-focused store for extra-activity records contributed by the CIP-0104
  * amendment's `ActivityRecorder_DsoRecordActivity` choice. Writes rows to
  * `extra_app_activity_record_store` and supports a round-trip read for
  * tests. The meta-row surface (`extra_app_activity_record_meta` — reads,
  * writes, `startAndEnsureMeta` bootstrap, archive bumping) lives on
  * `DbActivityRecordMetaStore`, which is the sole implementor of the
  * `ActivityRecordMetaStore` trait.
  */
class DbExtraActivityRecordStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
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

  private object Tables {
    val extraActivityRecords = "extra_app_activity_record_store"
  }

  private def historyId = updateHistory.historyId

  type ExtraActivityRecordT = DbExtraActivityRecordStore.ExtraActivityRecordT

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
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import cats.data.NonEmptyList
import com.daml.ledger.javaapi.data.Transaction
import com.digitalasset.canton.config.CantonRequireTypes.{String2066, String256M}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.resource.DbStorage.SQLActionBuilderChain
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_AnsEntryContext,
  ARC_DsoRules,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_CloseVoteRequestResult,
}
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  TransactionTreeUpdate,
  TreeUpdateOrOffsetCheckpoint,
}
import org.lfdecentralizedtrust.splice.store.db.{
  AcsJdbcTypes,
  DbKeyValueStore,
  StoreDescriptor,
  StoreDescriptorStore,
}
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider
import org.lfdecentralizedtrust.splice.store.events.DsoRulesCloseVoteRequest
import org.lfdecentralizedtrust.splice.store.*
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import org.lfdecentralizedtrust.splice.util.Trees
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.{JdbcProfile, PostgresProfile}

import scala.concurrent.{ExecutionContext, Future, Promise}
import java.time.Instant
import scala.util.{Success, Try}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Historical vote results (exercised DsoRules_CloseVoteRequest choices).
  *
  * Owns table `votes_store_results`, and the key `first_ingested_record_time` in the key value store.
  */
class DbVotesStore(
    storage: DbStorage,
    storeDescriptor: StoreDescriptor,
    kvStore: DbKeyValueStore,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, closeContext: CloseContext)
    extends AcsJdbcTypes
    with HistoricalVotesStore
    with LimitHelpers
    with NamedLogging {

  import DbVotesStore.*

  override val profile: JdbcProfile = PostgresProfile

  // Sort key: the effective date, or completedAt for rejected/expired votes that have none.
  private val sortKey = "coalesce(vote_effective_at, vote_completed_at)"

  private val storeIdP: Promise[Int] = Promise()
  private val storeIdF: Future[Int] = storeIdP.future
  private def assertStoreId: Int = storeIdF.value match {
    case Some(Success(storeId)) => storeId
    case _ =>
      throw new IllegalStateException(
        s"DbVotesStore ingestion sink $storeDescriptor was used before it was initialized"
      )
  }

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var ingestionStateInitialized: Boolean = false

  def ingestionSink: UpdateHistory.AdditionalIngestionSink =
    new UpdateHistory.AdditionalIngestionSink {
      override def initialize(historyId: Long)(implicit
          tc: TraceContext
      ): Future[Unit] = for {
        storeId <- StoreDescriptorStore.getStoreIdForDescriptor(
          storeDescriptor,
          storage,
        )
      } yield {
        logger.info(
          s"Initialized DbVotesStore ingestion sink $storeDescriptor with storeId=$storeId, attached to ingestion of UpdateHistory with historyId=$historyId"
        )
        // Ingestion sink may be initialized multiple times if the ingestion service crashes and recovers,
        // it's ok to assume getStoreIdForDescriptor returns the same id for the same descriptor.
        storeIdP.trySuccess(storeId).discard
      }

      override def prepareBatch(
          updates: NonEmptyList[TreeUpdateOrOffsetCheckpoint]
      )(implicit
          tc: TraceContext
      ): Seq[UpdateHistory.PreparedIngestionAction] = {
        val storeId = assertStoreId
        val actions: List[UpdateHistory.PreparedIngestionAction] = updates.toList.flatMap {
          case TreeUpdateOrOffsetCheckpoint.Update(TransactionTreeUpdate(tree), _) =>
            parseTransaction(tree, storeId)
          case _ => List.empty[UpdateHistory.PreparedIngestionAction]
        }
        if (ingestionStateInitialized) {
          actions
        } else {
          firstTransactionRecordTime(updates) match {
            case Some(firstRecordTime) =>
              SetFirstIngestedRecordTime(kvStore, firstRecordTime) +: actions
            case None =>
              actions
          }
        }
      }

      override def onTransactionCommitted(
          updates: NonEmptyList[TreeUpdateOrOffsetCheckpoint]
      )(implicit tc: TraceContext): Unit =
        // The actions from the previous `prepareBatch` have been committed. If they included
        // `SetFirstIngestedRecordTime`, we can set the flag to avoid running it in the future.
        // `SetFirstIngestedRecordTime` itself does nothing if the value is already set, this is just
        // a best-effort optimization to avoid unnecessary database writes.
        if (firstTransactionRecordTime(updates).isDefined) {
          ingestionStateInitialized = true
        }
    }

  private def firstTransactionRecordTime(
      updates: NonEmptyList[TreeUpdateOrOffsetCheckpoint]
  ): Option[CantonTimestamp] =
    updates.collectFirst {
      case TreeUpdateOrOffsetCheckpoint.Update(TransactionTreeUpdate(tree), _) =>
        CantonTimestamp.assertFromInstant(tree.getRecordTime)
    }

  /** The record time of the first transaction that the ingestion of this store has seen.
    * Vote results from before that time have to be backfilled.
    */
  def getFirstIngestedRecordTime()(implicit tc: TraceContext): Future[Option[CantonTimestamp]] = {
    import ScanKeyValueProvider.timestampCodec
    kvStore
      .readValueAndLogOnDecodingFailure[CantonTimestamp](firstIngestedRecordTimeKey)
      .value
  }

  override def listVoteRequestResults(
      filters: VoteResultsFilters,
      limit: Limit,
      after: Option[Long],
  )(implicit tc: TraceContext): Future[ResultsPage[DsoRules_CloseVoteRequestResult]] =
    for {
      sid <- storeIdF
      // The page token is a row_id, which is only used to look up the position in the sort order.
      afterCond = after.map(a => sql"""(#$sortKey, record_time, event_number) <
          (select #$sortKey, record_time, event_number
           from #$table where store_id = $sid and row_id = $a)""")
      where = and(filterConditions(sid, filters) ++ afterCond.toList.map(toSQLActionBuilderChain))
      rows <- futureUnlessShutdownToFuture(
        storage.query(
          (sql"select row_id, choice_result from #$table where " ++ where ++
            sql" order by #$sortKey desc, record_time desc, event_number desc limit ${sqlLimit(limit)}").toActionBuilder
            .as[(Long, String)],
          "DbVotesStore.listVoteRequestResults",
        )
      )
      limited = applyLimit("listVoteRequestResults", limit, rows)
    } yield ResultsPage(
      limited.map { case (_, json) => DsoRules_CloseVoteRequestResult.fromJson(json) },
      limited.lastOption.map(_._1),
    )

  override def countVoteRequestResults(filters: VoteResultsFilters)(implicit
      tc: TraceContext
  ): Future[Long] = {
    for {
      sid <- storeIdF
      count <- futureUnlessShutdownToFuture(
        storage.query(
          (sql"select count(*) from #$table where " ++ and(
            filterConditions(sid, filters)
          )).toActionBuilder
            .as[Long]
            .head,
          "DbVotesStore.countVoteRequestResults",
        )
      )
    } yield {
      count
    }
  }

  /** Dates that cannot be parsed are ignored, as if the filter was not set. */
  private def parseMicros(s: String): Option[Long] =
    Try(Instant.parse(s)).toOption.flatMap(CantonTimestamp.fromInstant(_).toOption).map(_.toMicros)

  private def and(conds: NonEmptyList[SQLActionBuilderChain]) =
    conds.reduceLeft((a, b) => (a ++ sql" and " ++ b).toActionBuilder)

  // No indexes support these filters: the table has one row per closed vote request,
  // so it stays small (thousands of rows) and is scanned. Only the sort order is index-backed.
  private def filterConditions(
      storeId: Int,
      f: VoteResultsFilters,
  ): NonEmptyList[SQLActionBuilderChain] = NonEmptyList(
    toSQLActionBuilderChain(sql"store_id = $storeId"),
    List(
      f.actionName.map(n => sql"vote_action_name like ${lengthLimited(s"%$n%")}"),
      f.accepted.map(a => sql"vote_accepted = $a"),
      f.requester.map(r => sql"vote_requester_name like ${lengthLimited(s"%$r%")}"),
      (f.effectiveFrom.flatMap(parseMicros), f.effectiveTo.flatMap(parseMicros)) match {
        case (Some(from), Some(to)) => Some(sql"vote_effective_at between $from and $to")
        case (Some(from), None) => Some(sql"vote_effective_at > $from")
        case (None, Some(to)) => Some(sql"vote_effective_at < $to")
        case (None, None) => None
      },
    ).flatten.map(toSQLActionBuilderChain),
  )

}

object DbVotesStore {
  def apply(
      dsoParty: PartyId,
      participantId: ParticipantId,
      storage: DbStorage,
      kvStore: DbKeyValueStore,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext,
      cc: CloseContext,
  ): DbVotesStore = {
    val storeDescriptor = StoreDescriptor(
      version = 1,
      name = "DbVotesStore",
      party = dsoParty,
      participant = participantId,
      key = Map(
        "dsoParty" -> dsoParty.toProtoPrimitive
      ),
    )
    new DbVotesStore(storage, storeDescriptor, kvStore, loggerFactory)
  }

  val table = "votes_store_results"
  val firstIngestedRecordTimeKey = "votes_store_first_ingested_record_time"

  /** Records the record time of the first transaction that the ingestion has seen, unless it is already set.
    * Runs in the same SQL transaction as the ingestion of the votes, so the value is recorded if and only if
    * the votes of the batch are.
    */
  final case class SetFirstIngestedRecordTime(
      kvStore: DbKeyValueStore,
      recordTime: CantonTimestamp,
  ) extends UpdateHistory.PreparedIngestionAction {
    import ScanKeyValueProvider.timestampCodec

    override def action = kvStore.setValueIfNotExistsAction(firstIngestedRecordTimeKey, recordTime)
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("recordTime", _.recordTime)
      )
    }
  }

  final case class InsertVoteResult(
      storeId: Int,
      recordTime: Long,
      synchronizerId: String2066,
      updateId: String2066,
      eventNumber: Int,
      voteActionName: String2066,
      voteRequesterName: String2066,
      voteAccepted: Boolean,
      voteTrackingCid: String2066,
      voteCompletedAt: Option[Long],
      voteEffectiveAt: Option[Long],
      choiceResult: String256M,
  ) extends UpdateHistory.PreparedIngestionAction {
    override def action = {
      sqlu"""
        insert into #$table(
          store_id,
          record_time,
          synchronizer_id,
          update_id,
          event_number,
          vote_action_name,
          vote_requester_name,
          vote_accepted,
          vote_tracking_cid,
          vote_completed_at,
          vote_effective_at,
          choice_result
        ) values (
          $storeId,
          ${recordTime},
          ${synchronizerId},
          ${updateId},
          ${eventNumber},
          ${voteActionName},
          ${voteRequesterName},
          ${voteAccepted},
          ${voteTrackingCid},
          ${voteCompletedAt},
          ${voteEffectiveAt},
          ${choiceResult}::jsonb
        )"""
    }
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("updateId", _.updateId.str.unquoted),
        param("voteActionName", _.voteActionName.str.unquoted),
        param("voteTrackingCid", _.voteTrackingCid.str.unquoted),
      )
    }
  }

  def parseTransaction(
      tx: Transaction,
      storeId: Int,
  )(implicit elc: ErrorLoggingContext): Seq[InsertVoteResult] = {
    val mapping = Trees.getLocalEventIndices(tx)
    val recordTime = CantonTimestamp.assertFromInstant(tx.getRecordTime).toMicros
    tx.getEventsById.asScala.iterator
      .collect { case (nodeId, DsoRulesCloseVoteRequest(node)) => nodeId -> node }
      .map { case (nodeId, node) =>
        val result = node.result.value
        val outcome = VoteRequestOutcome.parse(result.outcome)
        InsertVoteResult(
          storeId = storeId,
          recordTime = recordTime,
          synchronizerId = String2066.tryCreate(tx.getSynchronizerId),
          updateId = String2066.tryCreate(tx.getUpdateId),
          eventNumber = mapping(nodeId).intValue,
          voteActionName = String2066.tryCreate(actionName(result.request.action)),
          voteRequesterName = String2066.tryCreate(result.request.requester),
          voteAccepted = outcome match { case _: Accepted => true; case _ => false },
          voteTrackingCid =
            String2066.tryCreate(result.request.trackingCid.toScala.fold("")(_.contractId)),
          voteCompletedAt = Some(toMicros(result.completedAt)),
          voteEffectiveAt = outcome.effectiveAt.map(toMicros),
          choiceResult = String256M.tryCreate(
            result.toJson
          ),
        )
      }
      .toSeq
      .sortBy(_.eventNumber)
  }

  private def toMicros(at: Instant): Long = CantonTimestamp.assertFromInstant(at).toMicros

  private def actionName(action: ActionRequiringConfirmation): String = action match {
    case a: ARC_DsoRules => a.dsoAction.getClass.getSimpleName
    case a: ARC_AmuletRules => a.amuletRulesAction.getClass.getSimpleName
    case a: ARC_AnsEntryContext => a.ansEntryContextAction.getClass.getSimpleName
    case _ => ""
  }
}

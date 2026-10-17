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
import io.grpc.Status
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_AnsEntryContext,
  ARC_DsoRules,
  ExtActionRequiringConformation,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.voterequestoutcome.VRO_Expired
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_CloseVoteRequestResult,
  Reason,
  VoteRequest,
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
import org.lfdecentralizedtrust.splice.scan.store.{ScanKeyValueProvider, ScanStore}
import org.lfdecentralizedtrust.splice.store.events.DsoRulesCloseVoteRequest
import org.lfdecentralizedtrust.splice.store.*
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import org.lfdecentralizedtrust.splice.util.Trees
import slick.dbio.DBIO
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.{GetResult, JdbcProfile, PostgresProfile}

import scala.concurrent.{ExecutionContext, Future, Promise}
import java.time.Instant
import scala.util.{Failure, Success, Try}
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
    *
    * Record times are only ordered within a synchronizer, so using this as the boundary between
    * ingested and backfilled votes relies on votes only existing on the main synchronizer: votes
    * are signed by the SVs, which only exist there.
    */
  def getFirstIngestedRecordTime()(implicit tc: TraceContext): Future[Option[CantonTimestamp]] = {
    import ScanKeyValueProvider.timestampCodec
    kvStore
      .readValueAndLogOnDecodingFailure[CantonTimestamp](firstIngestedRecordTimeKey)
      .value
  }

  /** Where [[VotesStoreBackfilling]] should resume from, or `None` if it has not started yet. */
  def getBackfillingProgress()(implicit tc: TraceContext): Future[Option[VotesBackfillProgress]] = {
    import ScanKeyValueProvider.votesBackfillProgressCodec
    kvStore.readValueAndLogOnDecodingFailure[VotesBackfillProgress](backfillProgressKey).value
  }

  def persistBackfillingProgress(
      progress: VotesBackfillProgress
  )(implicit tc: TraceContext): Future[Unit] = {
    import ScanKeyValueProvider.votesBackfillProgressCodec
    kvStore.setValue(backfillProgressKey, progress)
  }

  def isBackfillingComplete()(implicit tc: TraceContext): Future[Boolean] =
    kvStore
      .readValueAndLogOnDecodingFailure[Boolean](backfillCompleteKey)
      .value
      .map(_.getOrElse(false))

  def markBackfillingComplete()(implicit tc: TraceContext): Future[Unit] =
    kvStore.setValue(backfillCompleteKey, true).map { _ =>
      logger.info("DbVotesStore backfilling is complete")
    }

  /** Inserts vote results backfilled from this Scan's txlog, and persists `progress` in the same
    * transaction, so that backfilling never inserts the same page twice.
    *
    * A vote is always the root of its transaction, so these are inserted with event number 0.
    */
  def insertTxLogVoteResults(
      results: Seq[ScanStore.TxLogVoteRequestResult],
      progress: VotesBackfillProgress,
  )(implicit tc: TraceContext): Future[Unit] = {
    val storeId = assertStoreId
    insertBackfilledPage(
      results.map(r =>
        toInsert(
          r.result,
          storeId,
          r.recordTime.toMicros,
          r.synchronizerId.toProtoPrimitive,
          r.updateId,
          eventNumber = 0,
        )
      ),
      progress,
      "DbVotesStore.insertTxLogVoteResults",
    )
  }

  /** Inserts vote results backfilled from a peer scan's `votes_store_results`, and persists
    * `progress` in the same transaction, so that backfilling never inserts the same page twice.
    */
  def insertPeerVoteResults(
      rows: Seq[PeerVoteResult],
      progress: VotesBackfillProgress,
  )(implicit tc: TraceContext): Future[Unit] = {
    val storeId = assertStoreId
    insertBackfilledPage(
      rows.map(_.toInsert(storeId)),
      progress,
      "DbVotesStore.insertPeerVoteResults",
    )
  }

  private def insertBackfilledPage(
      inserts: Seq[InsertVoteResult],
      progress: VotesBackfillProgress,
      operationName: String,
  )(implicit tc: TraceContext): Future[Unit] = {
    import ScanKeyValueProvider.votesBackfillProgressCodec
    import profile.api.jdbcActionExtensionMethods
    storage
      .queryAndUpdate(
        DBIO
          .seq(inserts.map(_.action)*)
          .andThen(kvStore.setValueAction(backfillProgressKey, progress))
          .transactionally,
        operationName,
      )
      .map(_ => ())
  }

  /** Serves [[VotesStoreBackfilling]] running on a peer: a page of this store's own rows, strictly
    * before `before`, ordered backwards by `(record_time, event_number)`.
    *
    * Deliberately does not use `row_id`: it is a local identity column, not comparable across scans.
    */
  def listBackfillPage(
      before: VotesBackfillCursor,
      limit: Int,
  )(implicit tc: TraceContext): Future[Seq[PeerVoteResult]] =
    for {
      sid <- storeIdF
      rows <- storage.query(
        sql"""select record_time, synchronizer_id, update_id, event_number, vote_action_name,
                     vote_requester_name, vote_accepted, vote_tracking_cid, vote_completed_at,
                     vote_effective_at, choice_result, error
              from #$table
              where store_id = $sid
                and (record_time, event_number) < (${before.recordTime.toMicros}, ${before.eventNumber})
              order by record_time desc, event_number desc
              limit $limit""".as[PeerVoteResult],
        "DbVotesStore.listBackfillPage",
      )
    } yield rows

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

  /** Fails with INVALID_ARGUMENT (HTTP 400) if the date cannot be parsed. */
  private def parseMicros(field: String, s: String): Long =
    Try(Instant.parse(s)).toOption
      .flatMap(CantonTimestamp.fromInstant(_).toOption)
      .getOrElse(
        throw Status.INVALID_ARGUMENT
          .withDescription(s"Invalid $field: '$s' is not an ISO-8601 instant")
          .asRuntimeException()
      )
      .toMicros

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
      (
        f.effectiveFrom.map(parseMicros("effective_from", _)),
        f.effectiveTo.map(parseMicros("effective_to", _)),
      ) match {
        case (Some(from), Some(to)) => Some(sql"vote_effective_at between $from and $to")
        case (Some(from), None) => Some(sql"vote_effective_at >= $from")
        case (None, Some(to)) => Some(sql"vote_effective_at <= $to")
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
  val backfillProgressKey = "votes_store_backfill_progress"
  val backfillCompleteKey = "votes_store_backfill_complete"

  /** Where [[VotesStoreBackfilling]] should resume: everything at `(recordTime, eventNumber)` or
    * later is already in `votes_store_results`; backfilling continues strictly before it.
    */
  final case class VotesBackfillCursor(recordTime: CantonTimestamp, eventNumber: Int)

  /** Where [[VotesStoreBackfilling]] reads from. Decided once, when backfilling starts, and
    * persisted so that a restart never switches sources halfway through.
    */
  sealed trait VotesBackfillSource extends Product with Serializable
  object VotesBackfillSource {
    case object TxLog extends VotesBackfillSource
    case object Peers extends VotesBackfillSource
  }

  final case class VotesBackfillProgress(cursor: VotesBackfillCursor, source: VotesBackfillSource)

  /** A vote result backfilled from a peer scan's `votes_store_results`, i.e. every column of that
    * table except `store_id` (which is local to each scan) and `row_id` (an opaque identity column
    * only used for the UI's pagination, never for backfilling).
    */
  final case class PeerVoteResult(
      recordTime: CantonTimestamp,
      synchronizerId: String,
      updateId: String,
      eventNumber: Int,
      voteActionName: String,
      voteRequesterName: String,
      voteAccepted: Boolean,
      voteTrackingCid: String,
      voteCompletedAt: Option[CantonTimestamp],
      voteEffectiveAt: Option[CantonTimestamp],
      choiceResult: String,
      error: Option[String],
  ) {
    def toInsert(storeId: Int): InsertVoteResult = InsertVoteResult(
      storeId = storeId,
      recordTime = recordTime.toMicros,
      synchronizerId = String2066.tryCreate(synchronizerId),
      updateId = String2066.tryCreate(updateId),
      eventNumber = eventNumber,
      voteActionName = String2066.tryCreate(voteActionName),
      voteRequesterName = String2066.tryCreate(voteRequesterName),
      voteAccepted = voteAccepted,
      voteTrackingCid = String2066.tryCreate(voteTrackingCid),
      voteCompletedAt = voteCompletedAt.map(_.toMicros),
      voteEffectiveAt = voteEffectiveAt.map(_.toMicros),
      choiceResult = String256M.tryCreate(choiceResult),
      error = error,
    )
  }

  implicit private val getResultPeerVoteResult: GetResult[PeerVoteResult] = GetResult { prs =>
    import prs.*
    PeerVoteResult(
      recordTime = CantonTimestamp.ofEpochMicro(<<[Long]),
      synchronizerId = <<[String],
      updateId = <<[String],
      eventNumber = <<[Int],
      voteActionName = <<[String],
      voteRequesterName = <<[String],
      voteAccepted = <<[Boolean],
      voteTrackingCid = <<[String],
      voteCompletedAt = <<?[Long].map(CantonTimestamp.ofEpochMicro),
      voteEffectiveAt = <<?[Long].map(CantonTimestamp.ofEpochMicro),
      choiceResult = <<[String],
      error = <<?[String],
    )
  }

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
      error: Option[String] = None,
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
          choice_result,
          error
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
          ${choiceResult}::jsonb,
          ${error}
        )
        on conflict do nothing
        """
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
      .flatMap { case (nodeId, event) =>
        lazy val eventNumber = mapping(nodeId).intValue
        // Only the nodes that are a CloseVoteRequest exercise can fail here, all other nodes are ignored.
        Try(event match {
          case DsoRulesCloseVoteRequest(node) =>
            Some(
              toInsert(
                node.result.value,
                storeId,
                recordTime,
                tx.getSynchronizerId,
                tx.getUpdateId,
                eventNumber,
              )
            )
          case _ => None
        }) match {
          case Success(insert) => insert
          case Failure(e) =>
            // Throwing would stop the ingestion of the whole update history, which can't continue past this update.
            // Instead, write a row that only identifies the vote, so that it can be fixed later.
            elc.error(
              s"Failed to decode the vote result in update ${tx.getUpdateId} at record time $recordTime. " +
                "Ingesting a placeholder instead.",
              e,
            )
            Some(
              toInsert(
                placeholderResult(recordTime),
                storeId,
                recordTime,
                tx.getSynchronizerId,
                tx.getUpdateId,
                eventNumber,
              ).copy(error = Some(e.getMessage.take(MaxErrorLength)))
            )
        }
      }
      .toSeq
      .sortBy(_.eventNumber)
  }

  private def toInsert(
      result: DsoRules_CloseVoteRequestResult,
      storeId: Int,
      recordTime: Long,
      synchronizerId: String,
      updateId: String,
      eventNumber: Int,
  ): InsertVoteResult = {
    val outcome = VoteRequestOutcome.parse(result.outcome)
    InsertVoteResult(
      storeId = storeId,
      recordTime = recordTime,
      synchronizerId = String2066.tryCreate(synchronizerId),
      updateId = String2066.tryCreate(updateId),
      eventNumber = eventNumber,
      voteActionName = String2066.tryCreate(actionName(result.request.action)),
      voteRequesterName = String2066.tryCreate(result.request.requester),
      voteAccepted = outcome match { case _: Accepted => true; case _ => false },
      voteTrackingCid =
        String2066.tryCreate(result.request.trackingCid.toScala.fold("")(_.contractId)),
      voteCompletedAt = Some(toMicros(result.completedAt)),
      voteEffectiveAt = outcome.effectiveAt.map(toMicros),
      choiceResult = String256M.tryCreate(result.toJson),
    )
  }

  /** A fake vote result for a vote that could not be decoded, so that its row can be read like any other.
    * The row still identifies the vote (record time, update id, event number) and records the error.
    *
    * This should not happen in practice: all SVs are expected to upgrade their apps before a new Daml version
    * is voted in, so every app can decode every vote result that is created. Throwing instead would stop the
    * ingestion of the whole update history, which is why this only logs an error.
    */
  private def placeholderResult(recordTime: Long): DsoRules_CloseVoteRequestResult = {
    val at = CantonTimestamp.assertFromLong(recordTime).toInstant
    new DsoRules_CloseVoteRequestResult(
      new VoteRequest(
        FailedToParseVote,
        FailedToParseVote,
        new ExtActionRequiringConformation(com.daml.ledger.javaapi.data.Unit.getInstance()),
        new Reason("", FailedToParseVote),
        at,
        java.util.Map.of(),
        java.util.Optional.of(new VoteRequest.ContractId(FailedToParseVote)),
        java.util.Optional.of(at),
      ),
      at,
      java.util.List.of(),
      java.util.List.of(),
      new VRO_Expired(com.daml.ledger.javaapi.data.Unit.getInstance()),
    )
  }

  val FailedToParseVote = "<failed to parse vote>"

  private val MaxErrorLength = 1000

  private def toMicros(at: Instant): Long = CantonTimestamp.assertFromInstant(at).toMicros

  private def actionName(action: ActionRequiringConfirmation): String = action match {
    case a: ARC_DsoRules => a.dsoAction.getClass.getSimpleName
    case a: ARC_AmuletRules => a.amuletRulesAction.getClass.getSimpleName
    case a: ARC_AnsEntryContext => a.ansEntryContextAction.getClass.getSimpleName
    case _ => ""
  }
}

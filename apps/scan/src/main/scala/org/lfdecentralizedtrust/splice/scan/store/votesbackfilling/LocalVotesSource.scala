// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.votesbackfilling

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.ledger.api.TransactionTreeUpdate
import org.lfdecentralizedtrust.splice.scan.store.ScanHistoryBackfilling
import org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore.PeerVoteResult
import org.lfdecentralizedtrust.splice.store.{PageLimit, UpdateHistory}

import scala.concurrent.{ExecutionContext, Future}

/** Where [[VotesStoreBackfilling]] reads the vote results from on a Scan that has them itself:
  * the update history of this Scan, if it starts with the transaction that founded the network.
  */
trait LocalVotesSource {
  import LocalVotesSource.*

  def historyStart()(implicit tc: TraceContext): Future[HistoryStart]

  /** The vote results in a page of at most `count` updates strictly before `before`, newest first. */
  def votesBefore(start: FromGenesis, before: CantonTimestamp, count: Int)(implicit
      tc: TraceContext
  ): Future[Page]
}

object LocalVotesSource {
  sealed trait HistoryStart extends Product with Serializable

  /** The update history has not ingested anything yet, so it is not known where it starts. */
  case object NotIngested extends HistoryStart

  /** The update history starts later than the network, e.g., because the SV joined later, or
    * because the participant was pruned before this Scan started ingesting.
    */
  case object NotFromGenesis extends HistoryStart

  /** The update history starts with the transaction that founded the network.
    *
    * @param ingestedUpTo up to where it has ingested, if it has ingested anything since the app started
    */
  final case class FromGenesis(
      synchronizerId: SynchronizerId,
      foundingRecordTime: CantonTimestamp,
      ingestedUpTo: Option[CantonTimestamp],
  ) extends HistoryStart

  /** @param oldestRecordTime the record time of the oldest update in the page (whether or not it has votes),
    *                         empty if there is no update before the requested record time.
    */
  final case class Page(votes: Seq[PeerVoteResult], oldestRecordTime: Option[CantonTimestamp])
}

class UpdateHistoryVotesSource(
    updateHistory: UpdateHistory,
    dsoParty: PartyId,
    migrationId: Long,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends LocalVotesSource
    with NamedLogging {
  import LocalVotesSource.*

  /** The founding transaction is the first update that is visible to the DSO party: before it,
    * `SV1Initializer` only allocates parties, vets packages and changes the topology, none of which is
    * an update of the DSO party. So it is enough to look at the first update of the update history
    * (import updates aside, which only exist after a hard synchronizer migration).
    */
  override def historyStart()(implicit tc: TraceContext): Future[HistoryStart] =
    if (!updateHistory.isReady) Future.successful(NotIngested)
    else
      updateHistory
        .getUpdatesWithoutImportUpdates(None, PageLimit.tryCreate(1))
        .map(_.headOption match {
          case None => NotIngested
          case Some(first)
              // Reading backwards from the votes store to the founding transaction stays within one migration id
              if first.migrationId == migrationId &&
                ScanHistoryBackfilling
                  .isFoundingTransactionTreeUpdate(first.update, dsoParty.toProtoPrimitive) =>
            FromGenesis(
              first.update.synchronizerId,
              first.update.update.recordTime,
              updateHistory.lastIngestedRecordTime,
            )
          case Some(_) => NotFromGenesis
        })

  override def votesBefore(start: FromGenesis, before: CantonTimestamp, count: Int)(implicit
      tc: TraceContext
  ): Future[Page] =
    updateHistory
      .getUpdatesBefore(
        migrationId,
        start.synchronizerId,
        beforeRecordTime = before,
        atOrAfterRecordTime = Some(start.foundingRecordTime),
        PageLimit.tryCreate(count),
      )
      .map { updates =>
        Page(
          votes = updates.flatMap(_.update.update match {
            case TransactionTreeUpdate(tx) => DbVotesStore.backfilledVoteResults(tx)
            case _ => Seq.empty
          }),
          oldestRecordTime = updates.map(_.update.update.recordTime).minOption,
        )
      }
}

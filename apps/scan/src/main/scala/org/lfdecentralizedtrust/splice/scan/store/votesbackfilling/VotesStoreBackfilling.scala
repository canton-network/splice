// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.votesbackfilling

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.{PekkoRetryableService, PekkoRetryingService}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore.{
  UndecodablePeerVoteResult,
  VotesBackfillCursor,
}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Backfills `votes_store_results` with the vote results from before the first transaction that
  * the votes store ingested (see `DbVotesStore.getFirstIngestedRecordTime`).
  *
  * - A Scan whose txlog is fully backfilled is migrated by the Flyway migration `V084__votes_store_from_txlog.sql`,
  *   which copies the vote results of the txlog and marks backfilling as complete.
  * - A Scan whose update history starts with the founding transaction of the network, at most
  *   `maxLocalHistory` before the votes store started ingesting, has all missing vote results in its own
  *   update history, and copies them from there. This is the Scan of the founding SV on a new network,
  *   which has no peers to ask. The bound keeps the amount of update history to read small: on a
  *   long-running network, reading it all would take very long.
  * - All other Scans read from peer Scans, until the peers say that there is nothing older.
  *
  * One Pekko stream, walking backwards in time one page at a time; modeled on
  * [[org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling.BulkStorageBackfilling]].
  */
class VotesStoreBackfilling(
    pollingInterval: NonNegativeFiniteDuration,
    pageSize: Int,
    maxLocalHistory: NonNegativeFiniteDuration,
    local: LocalVotesSource,
    peers: PeerVotesSource,
    votesStore: VotesBackfillingStore,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends NamedLogging
    with Spanning
    with PekkoRetryableService[VotesStoreBackfilling.Step] {

  import VotesStoreBackfilling.*

  private[votesbackfilling] def serviceSource()(implicit tc: TraceContext): Source[Step, NotUsed] =
    mksrc().concat(Source.never)

  private[votesbackfilling] def mksrc()(implicit tc: TraceContext): Source[Step, NotUsed] =
    Source.future(votesStore.isBackfillingComplete()).flatMapConcat { complete =>
      if (complete) {
        logger.info("Vote store backfilling is already complete, nothing to do")
        Source.empty
      } else {
        logger.info("Starting vote store backfilling")
        walk[State](NotStarted)(step)
      }
    }

  private def step(state: State)(implicit tc: TraceContext): Future[Emitted[State]] =
    state match {
      case NotStarted => initialize()
      case Backfilling(cursor, FromPeers) => peerStep(cursor)
      case Backfilling(cursor, FromLocalHistory(start)) => localStep(cursor, start)
    }

  /** Resumes from the persisted cursor, or starts at the first transaction the votes store ingested.
    *
    * Backfilling covers everything strictly before that transaction. Record times are only ordered
    * within a synchronizer, so this relies on votes only existing on the main synchronizer: votes
    * are signed by the SVs, which only exist there.
    */
  private def initialize()(implicit tc: TraceContext): Future[Emitted[State]] =
    votesStore.getFirstIngestedRecordTime().flatMap {
      case None =>
        logger.debug(
          "The vote store has not ingested any transaction yet, waiting before starting backfilling"
        )
        waitThen(NotStarted, WaitingForLiveIngestion)
      case Some(firstIngested) =>
        chooseSource(firstIngested).flatMap {
          case None => waitThen(NotStarted, WaitingForUpdateHistory)
          case Some(source) =>
            votesStore.getBackfillingCursor().flatMap {
              case Some(cursor) =>
                logger.info(s"Resuming vote store backfilling from $cursor, $source")
                continueWith(Backfilling(cursor, source), Resumed(cursor))
              case None =>
                val cursor = VotesBackfillCursor(firstIngested, 0)
                logger.info(s"Starting vote store backfilling from $cursor, $source")
                continueWith(Backfilling(cursor, source), Started(cursor))
            }
        }
    }

  /** Where to read the vote results before `firstIngested` from, or `None` if that cannot be decided yet.
    *
    * The local update history is only used if it has everything between the start of the network and
    * `firstIngested`: it starts with the founding transaction, and has ingested up to `firstIngested`.
    */
  private def chooseSource(
      firstIngested: CantonTimestamp
  )(implicit tc: TraceContext): Future[Option[BackfillingSource]] =
    local.historyStart().map {
      case LocalVotesSource.NotIngested =>
        logger.debug("The update history has not ingested anything yet, waiting")
        None
      case LocalVotesSource.NotFromGenesis =>
        Some(FromPeers)
      case start: LocalVotesSource.FromGenesis =>
        val localHistory =
          java.time.Duration.between(start.foundingRecordTime.toInstant, firstIngested.toInstant)
        if (localHistory.compareTo(maxLocalHistory.asJava) > 0) {
          logger.info(
            s"The update history starts with the founding transaction at ${start.foundingRecordTime}, " +
              s"but that is more than $maxLocalHistory before the vote store started ingesting at $firstIngested: " +
              "backfilling from peers instead of reading that much update history"
          )
          Some(FromPeers)
        } else if (start.ingestedUpTo.exists(_ >= firstIngested)) {
          Some(FromLocalHistory(start))
        } else {
          logger.debug(
            s"The update history has not ingested up to $firstIngested yet (${start.ingestedUpTo}), waiting"
          )
          None
        }
    }

  private def localStep(
      cursor: VotesBackfillCursor,
      start: LocalVotesSource.FromGenesis,
  )(implicit tc: TraceContext): Future[Emitted[State]] = {
    // A cursor that peer backfilling left in the middle of a transaction: read that transaction again,
    // inserting the vote results that are there already does not do anything.
    val before =
      if (cursor.eventNumber > 0) cursor.recordTime.immediateSuccessor else cursor.recordTime
    local
      .votesBefore(start, before, pageSize)
      .transformWith {
        case Success(LocalVotesSource.Page(_, None)) =>
          logger.info(
            "The update history has no updates before the current cursor: the founding transaction is reached, " +
              "vote store backfilling is complete"
          )
          votesStore.markBackfillingComplete().flatMap(_ => lastStep(Completed))
        case Success(LocalVotesSource.Page(votes, Some(oldestRecordTime))) =>
          val newCursor = VotesBackfillCursor(oldestRecordTime, 0)
          votesStore
            .insertPeerVoteResults(votes, newCursor)
            .map { _ =>
              logger.info(
                s"Backfilled ${votes.size} vote results from the update history, down to $newCursor"
              )
              Emitted(
                LocalPageBackfilled(votes.size, newCursor),
                Some(Backfilling(newCursor, FromLocalHistory(start))),
              )
            }
        case Failure(ex) =>
          retryLater(cursor, FromLocalHistory(start), WaitingForUpdateHistory, ex)
      }
  }

  private def peerStep(
      cursor: VotesBackfillCursor
  )(implicit tc: TraceContext): Future[Emitted[State]] =
    peers
      .votesBefore(cursor, pageSize)
      .transformWith {
        case Success(votes) if votes.isEmpty =>
          logger.info(
            "Peers report no votes before the current cursor: genesis is reached, vote store backfilling is complete"
          )
          votesStore.markBackfillingComplete().flatMap(_ => lastStep(Completed))
        case Success(votes) =>
          val newCursor = votes
            .minByOption(row => (row.recordTime, row.eventNumber))
            .map(row => VotesBackfillCursor(row.recordTime, row.eventNumber))
            .getOrElse(cursor)
          votesStore
            .insertPeerVoteResults(votes, newCursor)
            .map { _ =>
              logger.info(
                s"Backfilled ${votes.size} vote results from peers, down to $newCursor"
              )
              Emitted(
                PeerPageBackfilled(votes.size, newCursor),
                Some(Backfilling(newCursor, FromPeers)),
              )
            }
        case Failure(ex) =>
          retryLater(cursor, FromPeers, WaitingForPeers(cursor), ex)
      }

  private def retryLater(
      cursor: VotesBackfillCursor,
      source: BackfillingSource,
      step: Step,
      ex: Throwable,
  )(implicit tc: TraceContext): Future[Emitted[State]] = {
    ex match {
      case _: UndecodablePeerVoteResult =>
        // Every Scan can see and must be able to decode all vote results, so this is never expected.
        // Retrying does not help until this app is upgraded, but neither does giving up.
        logger.warn(
          s"Vote store backfilling is stuck at $cursor, retrying in ${pollingInterval}: ${ex.getMessage}",
          ex,
        )
      case _ =>
        logger.info(
          s"Could not backfill votes $source yet, retrying in ${pollingInterval}: ${ex.getMessage}"
        )
    }
    waitThen(Backfilling(cursor, source), step)
  }

  private def walk[S](initial: S)(stepFrom: S => Future[Emitted[S]]): Source[Step, NotUsed] =
    Source.unfoldAsync[Option[S], Step](Some(initial)) {
      case Some(state) => stepFrom(state).map(emitted => Some((emitted.nextState, emitted.step)))
      case None => Future.successful(None)
    }

  private def continueWith[S](state: S, step: Step): Future[Emitted[S]] =
    Future.successful(Emitted(step, Some(state)))

  private def lastStep[S](step: Step): Future[Emitted[S]] =
    Future.successful(Emitted(step, None))

  private def waitThen[S](state: S, step: Step): Future[Emitted[S]] =
    after(pollingInterval.underlying, actorSystem.scheduler)(continueWith(state, step))

  override def asPekkoRetryingService(
      automationConfig: AutomationConfig,
      backoffClock: Clock,
      retryProvider: RetryProvider,
  )(implicit tracer: Tracer): PekkoRetryingService[Step] =
    withNewTrace(description) { implicit traceContext => _ =>
      new PekkoRetryingService(
        serviceSource(),
        Sink.ignore,
        automationConfig,
        backoffClock,
        description,
        retryProvider,
        loggerFactory,
      )
    }
}

object VotesStoreBackfilling {
  val description = "VotesStoreBackfilling"

  private sealed trait BackfillingSource extends Product with Serializable
  private case object FromPeers extends BackfillingSource {
    override def toString: String = "from peers"
  }
  private final case class FromLocalHistory(start: LocalVotesSource.FromGenesis)
      extends BackfillingSource {
    override def toString: String = "from the update history"
  }

  private sealed trait State extends Product with Serializable
  private case object NotStarted extends State
  private final case class Backfilling(cursor: VotesBackfillCursor, source: BackfillingSource)
      extends State

  private final case class Emitted[+S](step: Step, nextState: Option[S])

  sealed trait Step extends Product with Serializable
  case object WaitingForLiveIngestion extends Step
  case object WaitingForUpdateHistory extends Step
  final case class Started(cursor: VotesBackfillCursor) extends Step
  final case class Resumed(cursor: VotesBackfillCursor) extends Step
  final case class PeerPageBackfilled(count: Int, cursor: VotesBackfillCursor) extends Step
  final case class LocalPageBackfilled(count: Int, cursor: VotesBackfillCursor) extends Step
  final case class WaitingForPeers(cursor: VotesBackfillCursor) extends Step
  case object Completed extends Step
}

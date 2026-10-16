// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.votesbackfilling

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  LifeCycle,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.{PekkoRetryableService, PekkoRetryingService}
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.{RetryProvider, SpliceLedgerClient}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore.{
  VotesBackfillCursor,
  VotesBackfillProgress,
  VotesBackfillSource,
}
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.TxLogBackfillingState
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

/** Backfills `votes_store_results` with the vote results from before the first transaction that
  * [[DbVotesStore]] ingested (see `DbVotesStore.getFirstIngestedRecordTime`): first from this
  * Scan's own txlog (the table the votes store replaces), and then, once that is exhausted, from
  * peer Scans - unless the txlog is itself fully backfilled, in which case there is nothing left
  * to do.
  *
  * One Pekko stream, walking backwards in time one page at a time; modeled on
  * [[org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling.BulkStorageBackfilling]].
  */
class VotesStoreBackfilling(
    pollingInterval: NonNegativeFiniteDuration,
    pageSize: Int,
    scanStore: ScanStore,
    votesStore: DbVotesStore,
    svName: String,
    ledgerClient: SpliceLedgerClient,
    automationConfig: AutomationConfig,
    upgradesConfig: UpgradesConfig,
    clock: Clock,
    override val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    actorSystem: ActorSystem,
    ec: ExecutionContextExecutor,
    mat: Materializer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends NamedLogging
    with Spanning
    with FlagCloseableAsync
    with RetryProvider.Has
    with PekkoRetryableService[VotesStoreBackfilling.Step] {

  import VotesStoreBackfilling.*

  private val peerConnection = new PeerBftScanConnection(
    scanStore,
    svName,
    ledgerClient,
    automationConfig,
    upgradesConfig,
    clock,
    retryProvider,
    loggerFactory,
  )

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
      case FromTxLog(cursor) => txLogStep(cursor)
      case FromPeers(cursor) => peerStep(cursor)
    }

  /** Decides once where to backfill from, and persists that decision together with the cursor, so
    * that a restart resumes from the same source. The txlog is only used if it is itself fully
    * backfilled: txlog backfilling takes weeks, and only Scans that joined recently are still doing
    * it, so they simply read everything from their peers instead. The decision waits until txlog
    * backfilling is initialized, which in turn waits for the update history backfilling to finish.
    *
    * Backfilling covers everything strictly before the first transaction the votes store ingested.
    * Record times are only ordered within a synchronizer, so this relies on votes only existing on
    * the main synchronizer: votes are signed by the SVs, which only exist there.
    */
  private def initialize()(implicit tc: TraceContext): Future[Emitted[State]] =
    votesStore.getBackfillingProgress().flatMap {
      case Some(progress) =>
        logger.info(s"Resuming vote store backfilling from $progress")
        continueWith(stateFor(progress), Resumed(progress))
      case None =>
        votesStore.getFirstIngestedRecordTime().flatMap {
          case Some(firstIngested) =>
            scanStore.multiDomainAcsStore.getTxLogBackfillingState().flatMap {
              // The txlog backfilling is only initialized once the update history backfilling
              // finished, so waiting here means waiting for the update history backfilling. On a
              // Scan that joined late, that can take weeks; on the founding SV's Scan, it is done
              // right away, so it reads its (complete) txlog instead of waiting forever for peers
              // that are all waiting for each other.
              case TxLogBackfillingState.NotInitialized =>
                logger.debug(
                  "Txlog backfilling is not initialized yet, waiting before deciding where to backfill votes from"
                )
                waitThen(NotStarted, WaitingForTxLogInitialization)
              case txLogState =>
                val source =
                  if (txLogState == TxLogBackfillingState.Complete) VotesBackfillSource.TxLog
                  else VotesBackfillSource.Peers
                val progress = VotesBackfillProgress(VotesBackfillCursor(firstIngested, 0), source)
                logger.info(
                  s"Vote store ingestion's first record time is $firstIngested and the txlog backfilling state " +
                    s"is $txLogState; starting backfilling with $progress"
                )
                votesStore
                  .persistBackfillingProgress(progress)
                  .map(_ => Emitted(Initialized(progress), Some(stateFor(progress))))
            }
          case None =>
            logger.debug(
              "The vote store has not ingested any transaction yet, waiting before starting backfilling"
            )
            waitThen(NotStarted, WaitingForLiveIngestion)
        }
    }

  private def stateFor(progress: VotesBackfillProgress): State = progress.source match {
    case VotesBackfillSource.TxLog => FromTxLog(progress.cursor)
    case VotesBackfillSource.Peers => FromPeers(progress.cursor)
  }

  /** Only reads the txlog once this Scan's own (independent) ACS/txlog ingestion has caught up to
    * the cursor on the main synchronizer, to avoid a gap: without this, votes with a record time
    * between the ACS store's current position and the cursor could still be inserted into the
    * txlog later, after backfilling has already moved past them. Like [[initialize]], this relies
    * on votes only existing on the main synchronizer.
    *
    * The txlog backfilling state is read before the page: if the txlog was fully backfilled before
    * reading, an empty page means there is nothing left. If it no longer is (e.g., the ACS store
    * was reset and is backfilling its txlog again), this switches to peers instead of completing.
    *
    * A vote is always the root of its transaction, so votes from the txlog get event number 0,
    * and no two of them share a record time.
    */
  private def txLogStep(
      cursor: VotesBackfillCursor
  )(implicit tc: TraceContext): Future[Emitted[State]] =
    scanStore.getDecentralizedSynchronizerId().flatMap { mainSynchronizer =>
      val acsCaughtUpTo = scanStore.acsLastIngestedRecordTimes.get(mainSynchronizer)
      if (!acsCaughtUpTo.exists(_ >= cursor.recordTime)) {
        logger.debug(
          s"Waiting for this Scan's own ACS/txlog ingestion on $mainSynchronizer (currently at $acsCaughtUpTo) " +
            s"to catch up to ${cursor.recordTime} before reading the txlog, to avoid a gap"
        )
        waitThen(FromTxLog(cursor), WaitingForOwnIngestion(cursor.recordTime))
      } else {
        for {
          txLogState <- scanStore.multiDomainAcsStore.getTxLogBackfillingState()
          results <- scanStore.listVoteRequestResultsBefore(Some(cursor.recordTime), pageSize)
          emitted <- results match {
            case Seq() if txLogState == TxLogBackfillingState.Complete =>
              logger.info(
                "The txlog is exhausted and itself fully backfilled: there is nothing before it, " +
                  "so vote store backfilling is complete"
              )
              votesStore.markBackfillingComplete().flatMap(_ => lastStep(Completed))
            case Seq() =>
              val progress = VotesBackfillProgress(cursor, VotesBackfillSource.Peers)
              logger.info(
                s"The txlog is exhausted but no longer fully backfilled ($txLogState), " +
                  s"continuing vote store backfilling from peers with $progress"
              )
              votesStore
                .persistBackfillingProgress(progress)
                .map(_ => Emitted(SwitchedToPeers(cursor), Some(FromPeers(cursor))))
            case results =>
              val oldest = results.map(_.recordTime).minOption.getOrElse(cursor.recordTime)
              val newCursor = VotesBackfillCursor(oldest, 0)
              votesStore
                .insertTxLogVoteResults(
                  results,
                  VotesBackfillProgress(newCursor, VotesBackfillSource.TxLog),
                )
                .map { _ =>
                  logger.info(
                    s"Backfilled ${results.size} vote results from the txlog, down to ${newCursor.recordTime}"
                  )
                  Emitted(TxLogPageBackfilled(results.size, newCursor), Some(FromTxLog(newCursor)))
                }
          }
        } yield emitted
      }
    }

  private def peerStep(
      cursor: VotesBackfillCursor
  )(implicit tc: TraceContext): Future[Emitted[State]] =
    peerConnection.connection
      .flatMap(_.getVotesBefore(cursor, pageSize))
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
            .insertPeerVoteResults(
              votes,
              VotesBackfillProgress(newCursor, VotesBackfillSource.Peers),
            )
            .map { _ =>
              logger.info(
                s"Backfilled ${votes.size} vote results from peers, down to $newCursor"
              )
              Emitted(PeerPageBackfilled(votes.size, newCursor), Some(FromPeers(newCursor)))
            }
        case Failure(ex) =>
          switchToTxLogIfNothingFetchedYet(cursor).flatMap {
            case Some(emitted) => Future.successful(emitted)
            case None =>
              logger.info(
                s"Could not backfill votes from peers yet, retrying in ${pollingInterval}: ${ex.getMessage}"
              )
              waitThen(FromPeers(cursor), WaitingForPeers(cursor))
          }
      }

  /** On the founding SV's Scan, txlog backfilling is briefly in progress before it completes, so
    * [[initialize]] may have chosen peers. All other Scans then wait for this one, so it would never
    * make progress. If nothing was fetched from peers yet and the txlog is now complete, read the
    * txlog instead. The cursor is then still the initial one (event number 0), which the txlog
    * phase can resume from.
    */
  private def switchToTxLogIfNothingFetchedYet(
      cursor: VotesBackfillCursor
  )(implicit tc: TraceContext): Future[Option[Emitted[State]]] =
    for {
      firstIngested <- votesStore.getFirstIngestedRecordTime()
      txLogState <- scanStore.multiDomainAcsStore.getTxLogBackfillingState()
      emitted <-
        if (
          firstIngested.contains(cursor.recordTime) && cursor.eventNumber == 0 &&
          txLogState == TxLogBackfillingState.Complete
        ) {
          val progress = VotesBackfillProgress(cursor, VotesBackfillSource.TxLog)
          logger.info(
            s"No peer can serve votes yet, nothing was fetched from peers, and the txlog is now fully " +
              s"backfilled: continuing vote store backfilling from the txlog with $progress"
          )
          votesStore
            .persistBackfillingProgress(progress)
            .map(_ => Some(Emitted(SwitchedToTxLog(cursor), Some(FromTxLog(cursor)))))
        } else Future.successful(None)
    } yield emitted

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

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq(
      SyncCloseable(
        "votes store backfilling peer connection",
        LifeCycle.close(peerConnection)(logger),
      )
    )
}

object VotesStoreBackfilling {
  val description = "VotesStoreBackfilling"

  private sealed trait State extends Product with Serializable
  private case object NotStarted extends State
  private final case class FromTxLog(cursor: VotesBackfillCursor) extends State
  private final case class FromPeers(cursor: VotesBackfillCursor) extends State

  private final case class Emitted[+S](step: Step, nextState: Option[S])

  sealed trait Step extends Product with Serializable
  case object WaitingForLiveIngestion extends Step
  case object WaitingForTxLogInitialization extends Step
  final case class Initialized(progress: VotesBackfillProgress) extends Step
  final case class Resumed(progress: VotesBackfillProgress) extends Step
  final case class WaitingForOwnIngestion(before: CantonTimestamp) extends Step
  final case class TxLogPageBackfilled(count: Int, cursor: VotesBackfillCursor) extends Step
  final case class SwitchedToPeers(cursor: VotesBackfillCursor) extends Step
  final case class SwitchedToTxLog(cursor: VotesBackfillCursor) extends Step
  final case class PeerPageBackfilled(count: Int, cursor: VotesBackfillCursor) extends Step
  final case class WaitingForPeers(cursor: VotesBackfillCursor) extends Step
  case object Completed extends Step
}

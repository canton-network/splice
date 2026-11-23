// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.MetricHandle.{Counter, Gauge}
import com.daml.metrics.api.MetricsContext
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
import org.lfdecentralizedtrust.splice.scan.config.{
  ScanStorageConfig,
  UpdateHistoryBackfillingFromBulkStorageConfig,
}
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider
import org.lfdecentralizedtrust.splice.scan.store.historystart.{HistoryReadiness, HistoryStart}
import org.lfdecentralizedtrust.splice.store.{
  S3BucketConnection,
  TreeUpdateWithMigrationId,
  UpdateHistory,
}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

/** Loads update history from this Scan's own bulk storage into the database, for the record times before its
  * history start `t` (#6253).
  *
  * A Scan whose history starts after genesis has ingested updates from `t` on. The update segments before its first
  * own segment are copied from its peers and committed to its bucket by bulk storage backfilling (#6252). This loads
  * the updates of those segments with record times up to and including `t`, segment by segment, starting from the
  * segment that contains the start of the analyzable time window (genesis when the window is unlimited).
  *
  * A segment is loaded once the committed segment progress covers it. After each loaded segment, the last loaded
  * segment is recorded, so a restart continues with the segment after it.
  */
class UpdateHistoryBackfillingFromBulkStorage(
    config: UpdateHistoryBackfillingFromBulkStorageConfig,
    storageConfig: ScanStorageConfig,
    updateHistory: UpdateHistory,
    segments: UpdateHistoryBackfillingFromBulkStorage.CommittedSegments,
    historyReadiness: HistoryReadiness,
    progress: UpdateHistoryBackfillingFromBulkStorage.Progress,
    loadedUpdates: Counter,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends NamedLogging
    with Spanning
    with PekkoRetryableService[UpdateHistoryBackfillingFromBulkStorage.Step] {

  import UpdateHistoryBackfillingFromBulkStorage.*

  private[bulk] def serviceSource()(implicit tc: TraceContext): Source[Step, NotUsed] =
    mksrc().concat(Source.never)

  /** The steps of the load; completes when everything up to the history start is loaded, or nothing is to be loaded.
    * Progress is read again each time the source is materialized, i.e. on each restart.
    */
  private[bulk] def mksrc()(implicit tc: TraceContext): Source[Step, NotUsed] =
    Source.unfoldAsync[State, Step](State.Starting) {
      case State.Starting => start()
      case State.Loading(historyStart, next) => continueWith(historyStart, next)
      case State.Finished => Future.successful(None)
    }

  private def start()(implicit tc: TraceContext): Future[Option[(State, Step)]] =
    if (!updateHistory.isReady) waitFor("the update history to be initialized")
    else
      historyReadiness.historyStart().flatMap {
        case None => waitFor("the history start of this Scan to be determined")
        case Some(HistoryStart.Genesis) =>
          logger.info("This Scan holds update history from genesis, nothing to load from bulk storage")
          Future.successful(Some((State.Finished, NothingToLoad)))
        case Some(HistoryStart.From(historyStart)) =>
          progress.readLastLoadedSegment.flatMap {
            case Some(loaded) =>
              logger.info(
                s"Continuing to load update history from bulk storage after $loaded, up to $historyStart"
              )
              continueWith(historyStart, Next.After(loaded))
            case None =>
              lowerBound() match {
                case None => waitFor("the start of the analyzable time window to be known")
                case Some(lower) =>
                  logger.info(
                    s"Loading update history from bulk storage from the segment containing $lower up to $historyStart"
                  )
                  continueWith(historyStart, Next.First(lower))
              }
          }
      }

  /** Where to start loading when nothing is loaded yet: the start of the analyzable time window, or genesis if the
    * window is unlimited. It moves forward with ingestion, but only matters until the first segment is loaded.
    */
  private def lowerBound(): Option[CantonTimestamp] =
    if (updateHistory.analyzableTimeWindowIsUnlimited) Some(CantonTimestamp.MinValue)
    else updateHistory.startAnalyzableTimeWindow

  private def continueWith(
      historyStart: CantonTimestamp,
      next: Next,
  )(implicit tc: TraceContext): Future[Option[(State, Step)]] =
    if (next.loadedUpTo >= historyStart) {
      logger.info(s"Update history is loaded from bulk storage up to the history start $historyStart")
      Future.successful(Some((State.Finished, Loaded(historyStart))))
    } else
      nextSegment(next).flatMap {
        case None =>
          logger.debug(s"No committed update segment after ${next.loadedUpTo} yet, waiting")
          waitThen(State.Loading(historyStart, next), WaitingForSegment(next.loadedUpTo))
        case Some(segment) =>
          for {
            count <- loadSegment(segment, historyStart)
            _ <- progress.persistLastLoadedSegment(segment)
            _ = loadedUpdates.inc(count.toLong)(MetricsContext.Empty)
            _ = logger.info(s"Loaded $count updates of the segment $segment from bulk storage")
          } yield Some(
            (State.Loading(historyStart, Next.After(segment)), SegmentLoaded(segment, count))
          )
      }

  /** The segment to load next, once it is committed. The first one is found in the bucket: the earliest committed
    * segment ending after the lower bound, which is the genesis segment on a new network. The following ones are on
    * the grid of segments, and those without updates have no objects.
    */
  private def nextSegment(next: Next)(implicit tc: TraceContext): Future[Option[UpdatesSegment]] =
    next match {
      case Next.First(lower) => segments.firstSegmentAfter(lower)
      case Next.After(loaded) =>
        val segment = UpdatesSegment(
          loaded.toTimestamp,
          storageConfig.computeBulkSnapshotTimeAfter(loaded.toTimestamp),
        )
        segments.committedUpTo.map(committed =>
          Option.when(committed.exists(_ >= segment.toTimestamp))(segment)
        )
    }

  private def loadSegment(segment: UpdatesSegment, historyStart: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Int] =
    segments
      .updates(segment)
      .filter(_.update.update.recordTime <= historyStart)
      .grouped(config.insertBatchSize)
      .mapConcat(sameMigrationRuns)
      .mapAsync(1)(insert)
      .runWith(Sink.fold(0)(_ + _))

  private def insert(batch: Vector[TreeUpdateWithMigrationId])(implicit
      tc: TraceContext
  ): Future[Int] =
    batch.headOption.fold(Future.successful(0)) { first =>
      val recordTimes = batch.map(_.update.update.recordTime)
      for {
        _ <- updateHistory.ensurePartitionsFor(
          recordTimes.minOption.getOrElse(first.update.update.recordTime),
          recordTimes.maxOption.getOrElse(first.update.update.recordTime),
        )
        _ <- updateHistory.destinationHistory.insert(
          first.migrationId,
          first.update.synchronizerId,
          batch.map(_.update),
        )
      } yield batch.size
    }

  private def waitFor(what: String)(implicit tc: TraceContext): Future[Option[(State, Step)]] = {
    logger.debug(s"Waiting for $what before loading update history from bulk storage")
    waitThen(State.Starting, Waiting(what))
  }

  private def waitThen(state: State, step: Step): Future[Option[(State, Step)]] =
    after(config.pollingInterval.underlying, actorSystem.scheduler)(
      Future.successful(Some((state, step)))
    )

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

object UpdateHistoryBackfillingFromBulkStorage {
  val description = "UpdateHistoryBackfillingFromBulkStorage"

  val lastLoadedSegmentKvStoreKey = "update_history_from_bulk_storage_cursor"

  /** Splits a batch into runs of updates with the same migration id, keeping their order, because one insert is for
    * one migration id.
    */
  private[bulk] def sameMigrationRuns(
      batch: Seq[TreeUpdateWithMigrationId]
  ): Seq[Vector[TreeUpdateWithMigrationId]] =
    batch.foldLeft(Vector.empty[Vector[TreeUpdateWithMigrationId]]) {
      case (done :+ (run @ (first +: _)), update) if first.migrationId == update.migrationId =>
        done :+ (run :+ update)
      case (runs, update) => runs :+ Vector(update)
    }

  /** The update segments in this Scan's committed bucket. */
  trait CommittedSegments {

    /** The earliest committed segment that ends after `after`, None while there is none. */
    def firstSegmentAfter(after: CantonTimestamp)(implicit
        tc: TraceContext
    ): Future[Option[UpdatesSegment]]

    /** The end of the latest committed segment. */
    def committedUpTo(implicit tc: TraceContext): Future[Option[CantonTimestamp]]

    /** The updates of a committed segment, in the order they were written. */
    def updates(segment: UpdatesSegment)(implicit
        tc: TraceContext
    ): Source[TreeUpdateWithMigrationId, NotUsed]
  }

  class BucketCommittedSegments(
      reader: BulkStorageReader,
      committedConnection: S3BucketConnection,
      config: UpdateHistoryBackfillingFromBulkStorageConfig,
  )(implicit actorSystem: ActorSystem, ec: ExecutionContext)
      extends CommittedSegments {

    override def firstSegmentAfter(after: CantonTimestamp)(implicit
        tc: TraceContext
    ): Future[Option[UpdatesSegment]] =
      reader.getCommittedSegmentAfter(after)

    override def committedUpTo(implicit tc: TraceContext): Future[Option[CantonTimestamp]] =
      reader.updateHistoryCommittedProgress.readLatestProcessedSegment.map(_.map(_.toTimestamp))

    override def updates(segment: UpdatesSegment)(implicit
        tc: TraceContext
    ): Source[TreeUpdateWithMigrationId, NotUsed] =
      Source
        .future(
          reader.getCommittedUpdateObjectsInSegment(
            segment,
            ScanStorageConfig.Encoding.ProtobufJson,
          )
        )
        .mapConcat(BulkStorageUpdateDecoder.protobufJsonObjectsInOrder)
        .flatMapConcat { key =>
          BulkStorageUpdateDecoder.decodeObject(
            key,
            Source.futureSource(committedConnection.readObject(key)),
            config.maxLineBytes,
            readTimeout,
          )
        }
  }

  private val readTimeout: FiniteDuration = 1.minute

  /** The last loaded segment, kept in the key-value store. */
  class Progress(
      kvProvider: ScanKeyValueProvider,
      loadedUpTo: Gauge[CantonTimestamp],
      override val loggerFactory: NamedLoggerFactory,
  ) extends NamedLogging {
    import ScanKeyValueProvider.updatesSegmentCodec

    def readLastLoadedSegment(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ): Future[Option[UpdatesSegment]] =
      kvProvider.store
        .readValueAndLogOnDecodingFailure[UpdatesSegment](lastLoadedSegmentKvStoreKey)
        .value
        .map { loaded =>
          loaded.foreach(segment => loadedUpTo.updateValue(segment.toTimestamp))
          loaded
        }

    def persistLastLoadedSegment(segment: UpdatesSegment)(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ): Future[Unit] =
      kvProvider.store
        .setValue(lastLoadedSegmentKvStoreKey, segment)
        .map(_ => loadedUpTo.updateValue(segment.toTimestamp))

    def reset(implicit tc: TraceContext): Future[Unit] =
      kvProvider.store.deleteKey(lastLoadedSegmentKvStoreKey)
  }

  private sealed trait State
  private object State {
    case object Starting extends State
    final case class Loading(historyStart: CantonTimestamp, next: Next) extends State
    case object Finished extends State
  }

  /** Where loading continues: with the first segment ending after a lower bound, or after the last loaded segment. */
  private sealed trait Next {
    def loadedUpTo: CantonTimestamp
  }
  private object Next {
    final case class First(lower: CantonTimestamp) extends Next {
      override def loadedUpTo: CantonTimestamp = lower
    }
    final case class After(loaded: UpdatesSegment) extends Next {
      override def loadedUpTo: CantonTimestamp = loaded.toTimestamp
    }
  }

  sealed trait Step extends Product with Serializable
  final case class Waiting(what: String) extends Step
  final case class WaitingForSegment(after: CantonTimestamp) extends Step
  final case class SegmentLoaded(segment: UpdatesSegment, updates: Int) extends Step
  final case class Loaded(upTo: CantonTimestamp) extends Step
  case object NothingToLoad extends Step
}

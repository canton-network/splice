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
  UpdateHistoryLoadConfig,
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

/** Loads update history from this Scan's own bulk storage into the database,
  * for the record times before the updates it ingested itself (#6253).
  *
  * A Scan whose history starts after genesis, at `t`, ingests updates from
  * about `t` on. The update segments before its first own segment are copied
  * from its peers and committed to its bucket by bulk storage backfilling
  * (#6252). This loads the updates of those segments that are older than the
  * earliest update stored after `t`, segment by segment, starting from the
  * segment that contains the start of the analyzable time window (genesis when
  * the window is unlimited). Updates that are already stored are skipped, so a
  * segment can be loaded again after a restart, and updates that were stored by
  * other means are left alone.
  *
  * A segment is loaded once the committed segment progress covers it. After
  * each loaded segment, the last loaded segment is recorded, so a restart
  * continues with the segment after it.
  */
class UpdateHistoryLoadFromBulkStorage(
    config: UpdateHistoryLoadConfig,
    storageConfig: ScanStorageConfig,
    updateHistory: UpdateHistory,
    segments: UpdateHistoryLoadFromBulkStorage.CommittedSegments,
    historyReadiness: HistoryReadiness,
    progress: UpdateHistoryLoadFromBulkStorage.Progress,
    loadedUpdates: Counter,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends NamedLogging
    with Spanning
    with PekkoRetryableService[UpdateHistoryLoadFromBulkStorage.Step] {

  import UpdateHistoryLoadFromBulkStorage.*

  private[bulk] def serviceSource()(implicit tc: TraceContext): Source[Step, NotUsed] =
    mksrc().concat(Source.never)

  /** The steps of the load; completes when everything up to the history start
    * is loaded, or nothing is to be loaded. Progress is read again each time
    * the source is materialized, i.e. on each restart.
    */
  private[bulk] def mksrc()(implicit tc: TraceContext): Source[Step, NotUsed] =
    Source.unfoldAsync[State, Step](State.Starting) {
      case State.Starting => start()
      case State.Loading(loadBefore, next) => continueWith(loadBefore, next)
      case State.Finished => Future.successful(None)
    }

  private def start()(implicit tc: TraceContext): Future[Option[(State, Step)]] =
    if (!updateHistory.isReady) waitFor("the update history to be initialized")
    else
      historyReadiness.historyStart().flatMap {
        case None => waitFor("the history start of this Scan to be determined")
        case Some(HistoryStart.Genesis) =>
          logger
            .info("This Scan holds update history from genesis, nothing to load from bulk storage")
          Future.successful(Some((State.Finished, NothingToLoad)))
        case Some(HistoryStart.From(historyStart)) =>
          updateHistory.getEarliestRecordTimeAfter(historyStart).flatMap {
            case None =>
              waitFor(s"this Scan to ingest its first update after its history start $historyStart")
            case Some(loadBefore) =>
              progress.readLastLoadedSegment.flatMap {
                case Some(loaded) =>
                  logger.info(
                    s"Continuing to load update history from bulk storage after $loaded, up to $loadBefore (exclusive)"
                  )
                  continueWith(loadBefore, Next.After(loaded))
                case None =>
                  lowerBound() match {
                    case None => waitFor("the start of the analyzable time window to be known")
                    case Some(lower) =>
                      logger.info(
                        s"Loading update history from bulk storage from the segment containing $lower up to $loadBefore (exclusive)"
                      )
                      continueWith(loadBefore, Next.First(lower))
                  }
              }
          }
      }

  /** Where to start loading when nothing is loaded yet: the start of the
    * analyzable time window, or genesis if the window is unlimited. It moves
    * forward with ingestion, but only matters until the first segment is
    * loaded.
    */
  private def lowerBound(): Option[CantonTimestamp] =
    if (updateHistory.analyzableTimeWindowIsUnlimited) Some(CantonTimestamp.MinValue)
    else updateHistory.startAnalyzableTimeWindow

  /** @param loadBefore the earliest record time of an update stored after the
    *   history start; everything older is loaded
    */
  private def continueWith(
      loadBefore: CantonTimestamp,
      next: Next,
  )(implicit tc: TraceContext): Future[Option[(State, Step)]] =
    if (next.loadedUpTo >= loadBefore) {
      logger.info(s"Update history is loaded from bulk storage up to $loadBefore (exclusive)")
      Future.successful(Some((State.Finished, Loaded(loadBefore))))
    } else
      nextSegment(next).flatMap {
        case None =>
          logger.debug(s"No committed update segment after ${next.loadedUpTo} yet, waiting")
          waitThen(State.Loading(loadBefore, next), WaitingForSegment(next.loadedUpTo))
        case Some(segment) =>
          for {
            inserted <- loadSegment(segment, loadBefore)
            _ <- progress.persistLastLoadedSegment(segment)
            _ = logger.info(s"Loaded $inserted updates of the segment $segment from bulk storage")
          } yield Some(
            (State.Loading(loadBefore, Next.After(segment)), SegmentLoaded(segment, inserted))
          )
      }

  /** The segment to load next, once it is committed. The first one is found in
    * the bucket: the earliest committed segment ending after the lower bound,
    * which is the genesis segment on a new network. The following ones are on
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

  /** Inserts the updates of a segment that are older than `loadBefore` and not
    * stored yet, returning how many.
    */
  private def loadSegment(segment: UpdatesSegment, loadBefore: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Int] =
    segments
      .updates(segment)
      .filter(_.update.update.recordTime < loadBefore)
      .grouped(config.batchSize)
      .mapConcat(sameMigrationRuns)
      .prefixAndTail(1)
      .flatMapConcat { case (firstBatch, otherBatches) =>
        // The updates of a segment are in record time order, so the partitions
        // from the first one to the end of the segment cover all of them.
        firstBatch.headOption.flatMap(_.headOption) match {
          case None => Source.empty
          case Some(first) =>
            val last =
              if (segment.toTimestamp < loadBefore) segment.toTimestamp else loadBefore
            Source
              .future(updateHistory.ensurePartitionsFor(first.update.update.recordTime, last))
              .flatMapConcat(_ => Source(firstBatch).concat(otherBatches))
        }
      }
      .mapAsync(1)(insertNotStored)
      .runWith(Sink.fold(0)(_ + _))

  private def insertNotStored(batch: Vector[TreeUpdateWithMigrationId])(implicit
      tc: TraceContext
  ): Future[Int] =
    batch.headOption.fold(Future.successful(0)) { first =>
      val recordTimes = batch.map(_.update.update.recordTime)
      for {
        stored <- updateHistory.getStoredUpdateIds(
          first.migrationId,
          recordTimes.minOption.getOrElse(first.update.update.recordTime),
          recordTimes.maxOption.getOrElse(first.update.update.recordTime),
          batch.map(_.update.update.updateId),
        )
        notStored = batch.filterNot(u => stored.contains(u.update.update.updateId))
        _ <-
          if (notStored.isEmpty) Future.unit
          else
            updateHistory.destinationHistory
              .insert(first.migrationId, first.update.synchronizerId, notStored.map(_.update))
              .map(_ => loadedUpdates.inc(notStored.size.toLong)(MetricsContext.Empty))
      } yield notStored.size
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

object UpdateHistoryLoadFromBulkStorage {
  val description = "UpdateHistoryLoadFromBulkStorage"

  val lastLoadedSegmentKvStoreKey = "update_history_from_bulk_storage_cursor"

  /** Why loading update history from bulk storage is not started, None if it is
    * started.
    */
  def notStartedBecause(
      enabled: Boolean,
      backfillingFromPeersEnabled: Boolean,
  ): Option[NotStarted] =
    if (!enabled) Some(NotStarted.Disabled)
    else if (backfillingFromPeersEnabled) Some(NotStarted.BackfillingFromPeers)
    else None

  sealed abstract class NotStarted(val description: String) extends Product with Serializable
  object NotStarted {
    case object Disabled extends NotStarted("Loading update history from bulk storage is disabled")

    /** With backfilling from peers enabled, it fills the same history, and the
      * history start is genesis once it is complete.
      */
    case object BackfillingFromPeers
        extends NotStarted(
          "Not loading update history from bulk storage although bulk-storage.update-history-load.enabled is set, " +
            "because update history backfilling from peers is enabled too. To load it from bulk storage, " +
            "set update-history-backfill-enabled = false (Helm: updateHistoryBackfilling.enabled, " +
            "Pulumi: scanApp.updateHistoryBackfillingFromPeers.enabled)"
        )
  }

  /** Splits a batch into runs of updates with the same migration id, keeping
    * their order, because one insert is for one migration id.
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

    /** The earliest committed segment that ends after `after`, None while there
      * is none.
      */
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
      config: UpdateHistoryLoadConfig,
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
            // Only start the download when the object is decoded.
            Source.lazyFutureSource(() => committedConnection.readObject(key)),
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

  /** Where loading continues: with the first segment ending after a lower
    * bound, or after the last loaded segment.
    */
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

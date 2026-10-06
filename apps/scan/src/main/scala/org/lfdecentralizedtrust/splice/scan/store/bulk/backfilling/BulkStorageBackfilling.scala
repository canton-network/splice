// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import cats.syntax.traverse.*
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
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageBackfillingConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdatesSegment
import org.lfdecentralizedtrust.splice.scan.store.historystart.{HistoryStart, ScanHistoryStart}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.TimestampWithMigrationId

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class BulkStorageBackfilling(
    config: BulkStorageBackfillingConfig,
    storageConfig: ScanStorageConfig,
    currentMigrationId: Long,
    listing: BulkObjectListing,
    copier: ObjectCopier,
    progress: BackfillingProgress,
    upperBound: BulkStorageBackfilling.BackfillUpperBound,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends NamedLogging
    with Spanning
    with PekkoRetryableService[BulkStorageBackfilling.Step] {

  import BulkStorageBackfilling.*

  private type Next = Future[Option[(State, Step)]]

  private[backfilling] def serviceSource()(implicit tc: TraceContext): Source[Step, NotUsed] =
    mksrc().concat(Source.never)

  private[backfilling] def mksrc()(implicit tc: TraceContext): Source[Step, NotUsed] =
    Source.future(progress.isComplete).flatMapConcat { complete =>
      if (complete) {
        logger.info("Bulk storage backfilling from peers already complete, nothing to do")
        Source.empty
      } else Source.unfoldAsync[State, Step](CopyUpdates)(s => step(s))
    }

  private def step(state: State)(implicit tc: TraceContext): Next =
    state match {
      case CopyUpdates =>
        withBackfillEnd(state)(copyNextSegment)
      case CopySnapshots(lastRequested) =>
        withBackfillEnd(state)(copyNextSnapshot(lastRequested, _))
      case Finish => progress.markComplete().flatMap(_ => next(Done, Completed))
      case Done => Future.successful[Option[(State, Step)]](None)
    }

  private def withBackfillEnd(state: State)(
      copy: CantonTimestamp => Next
  )(implicit tc: TraceContext): Next =
    upperBound.end.flatMap {
      case BackfillEnd.HistoryComplete =>
        logger.info("This Scan holds history from genesis, there is nothing to copy from the peers")
        next(Finish, NothingToCopy)
      case BackfillEnd.NotYetKnown =>
        logger.debug("The history start of this Scan is not known yet, waiting")
        waitThen(state, WaitingForHistoryStart)
      case BackfillEnd.CopyUpTo(firstOwnSegmentStart) => copy(firstOwnSegmentStart)
    }

  private def next(state: State, step: Step): Next =
    Future.successful(Some((state, step)))

  private def waitThen(state: State, step: Step): Next =
    after(config.pollingInterval.underlying, actorSystem.scheduler)(next(state, step))

  private def copyNextSegment(copyUpTo: CantonTimestamp)(implicit tc: TraceContext): Next =
    progress.readUpdatesCursor.flatMap {
      case Some(copied) if copied.toTimestamp.timestamp >= copyUpTo =>
        val upTo = copied.toTimestamp.timestamp
        logger.info(s"Update objects are copied up to $upTo, where this Scan's own segments start")
        next(CopySnapshots(None), UpdatesCopied(upTo))
      case Some(copied) => copySegment(segmentAfter(copied))
      case None =>
        firstSegment(copyUpTo).flatMap {
          case Some(first) => copySegment(first)
          case None =>
            logger.debug(s"Not enough peers hold update objects up to $copyUpTo yet, waiting")
            waitThen(CopyUpdates, WaitingForPeers(CantonTimestamp.MinValue))
        }
    }

  private def segmentAfter(copied: UpdatesSegment): UpdatesSegment =
    UpdatesSegment(
      copied.toTimestamp,
      TimestampWithMigrationId(
        storageConfig.computeBulkSnapshotTimeAfter(copied.toTimestamp.timestamp),
        currentMigrationId,
      ),
    )

  private def firstSegment(copyUpTo: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[UpdatesSegment]] =
    listing
      .updateObjects(CantonTimestamp.MinValue, copyUpTo, config.pageSize, availableAt = copyUpTo)
      .flatMap {
        case PeerListing.Available(page, _) =>
          Future.fromTry(segmentsOf(page.objects)).map(_.headOption)
        case PeerListing.NotAvailableYet => Future.successful(None)
      }

  private def copySegment(segment: UpdatesSegment)(implicit tc: TraceContext): Next = {
    val (from, to) = (segment.fromTimestamp.timestamp, segment.toTimestamp.timestamp)
    listing.updateObjects(from, to, config.pageSize, availableAt = to).flatMap {
      case PeerListing.NotAvailableYet =>
        logger.debug(s"Not enough peers hold the update segment $from - $to yet, waiting")
        waitThen(CopyUpdates, WaitingForPeers(from))
      case PeerListing.Available(page, holders) =>
        for {
          _ <- copier.copy(page.objects, holders)
          _ <- progress.persistUpdatesCursor(segment)
          step <- next(CopyUpdates, SegmentCopied(segment, page.objects.size))
        } yield step
    }
  }

  private def copyNextSnapshot(
      lastRequested: Option[CantonTimestamp],
      copyUpTo: CantonTimestamp,
  )(implicit tc: TraceContext): Next =
    for {
      cursor <- progress.readSnapshotsCursor
      requested <- lastRequested.orElse(cursor.map(_.timestamp)) match {
        case Some(last) =>
          Future.successful[Option[CantonTimestamp]](
            Some(storageConfig.computeBulkSnapshotTimeAfter(last))
          )
        case None => firstSegment(copyUpTo).map(_.map(_.toTimestamp.timestamp))
      }
      result <- requested match {
        case None =>
          logger.debug("The peers list no update segment yet, waiting before walking the snapshots")
          waitThen(CopySnapshots(lastRequested), WaitingForPeers(CantonTimestamp.MinValue))
        case Some(ts) if ts > copyUpTo => next(Finish, SnapshotsCopied)
        case Some(ts) =>
          def nothingNewer(reason: String): Next = {
            logger.debug(s"$reason, waiting for the snapshot at $ts")
            waitThen(CopySnapshots(lastRequested), WaitingForPeers(ts))
          }
          listing.snapshotObjectsAtOrBefore(ts).flatMap {
            case PeerListing.NotAvailableYet =>
              nothingNewer(s"Not enough peers hold the snapshot at $ts yet")
            case PeerListing.Available(None, _) =>
              nothingNewer(s"The peers have no committed snapshot at $ts")
            case PeerListing.Available(Some(snapshot), _)
                if cursor.exists(_.timestamp >= snapshot.recordTime) =>
              nothingNewer(s"The peers have no snapshot after ${snapshot.recordTime}")
            case PeerListing.Available(Some(snapshot), _) if snapshot.objects.isEmpty =>
              next(CopySnapshots(Some(snapshot.recordTime)), SnapshotSkipped(snapshot.recordTime))
            case PeerListing.Available(Some(snapshot), holders) =>
              val copied = TimestampWithMigrationId(snapshot.recordTime, currentMigrationId)
              for {
                _ <- copier.copy(snapshot.objects, holders)
                _ <- progress.persistSnapshotsCursor(copied)
                step <- next(
                  CopySnapshots(Some(snapshot.recordTime)),
                  SnapshotCopied(copied, snapshot.objects.size),
                )
              } yield step
          }
      }
    } yield result

  private def segmentsOf(objects: Seq[ObjectKeyAndChecksum]): Try[Seq[UpdatesSegment]] =
    objects
      .map(obj => storageConfig.getSegmentFolderOfObjectKey(obj.key))
      .distinct
      .toList
      .traverse(segmentOfFolder)
      .map(_.sortBy(_.fromTimestamp))

  private def segmentOfFolder(folder: String): Try[UpdatesSegment] =
    storageConfig.getStartAndEndTimestampsForFolder(folder) match {
      case Right((from, to)) =>
        Success(
          UpdatesSegment(
            TimestampWithMigrationId(from, currentMigrationId),
            TimestampWithMigrationId(to, currentMigrationId),
          )
        )
      case Left(err) => Failure(new IllegalStateException(err))
    }

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

object BulkStorageBackfilling {
  val description = "BulkStorageBackfilling"

  /** How far this Scan has to copy from its peers: up to its first own segment start, nothing (its history starts at
    * genesis), or not known yet (its history start is not recorded).
    */
  sealed trait BackfillEnd

  object BackfillEnd {
    final case class CopyUpTo(firstOwnSegmentStart: CantonTimestamp) extends BackfillEnd
    case object HistoryComplete extends BackfillEnd
    case object NotYetKnown extends BackfillEnd
  }

  trait BackfillUpperBound {
    def end(implicit tc: TraceContext): Future[BackfillEnd]
  }

  /** Derives the [[BackfillEnd]] from the recorded history start. */
  class UpToFirstOwnSegment(historyStart: ScanHistoryStart, storageConfig: ScanStorageConfig)(
      implicit ec: ExecutionContext
  ) extends BackfillUpperBound {
    override def end(implicit tc: TraceContext): Future[BackfillEnd] =
      historyStart.get.map {
        case None => BackfillEnd.NotYetKnown
        case Some(HistoryStart.Genesis) => BackfillEnd.HistoryComplete
        case Some(start: HistoryStart.From) =>
          BackfillEnd.CopyUpTo(start.firstOwnSegmentStart(storageConfig))
      }
  }

  private sealed trait State
  private case object CopyUpdates extends State
  private final case class CopySnapshots(lastRequested: Option[CantonTimestamp]) extends State
  private case object Finish extends State
  private case object Done extends State

  sealed trait Step
  final case class SegmentCopied(segment: UpdatesSegment, objects: Int) extends Step
  final case class UpdatesCopied(upTo: CantonTimestamp) extends Step
  final case class SnapshotSkipped(at: CantonTimestamp) extends Step
  final case class SnapshotCopied(snapshot: TimestampWithMigrationId, objects: Int) extends Step
  case object SnapshotsCopied extends Step
  final case class WaitingForPeers(at: CantonTimestamp) extends Step
  case object WaitingForHistoryStart extends Step
  case object NothingToCopy extends Step
  case object Completed extends Step
}

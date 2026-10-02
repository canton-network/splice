// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

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
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.BulkStorageObjects
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageBackfillingConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdatesSegment
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.TimestampWithMigrationId

import scala.concurrent.{ExecutionContext, Future}

class BulkStorageBackfilling(
    config: BulkStorageBackfillingConfig,
    storageConfig: ScanStorageConfig,
    currentMigrationId: Long,
    listing: BulkObjectListing,
    copier: ObjectCopier,
    progress: BackfillingProgress,
    upperBound: BackfillUpperBound,
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
    Source.future(progress.isComplete).flatMapConcat {
      case true =>
        logger.info("Bulk storage backfilling from peers already complete, nothing to do")
        Source.empty
      case false =>
        Source.unfoldAsync[State, Step](CopyUpdates(None))(s => step(s))
    }

  private def step(state: State)(implicit tc: TraceContext): Next =
    state match {
      case CopyUpdates(nextPageToken) =>
        withBackfillEnd(state)(copyUpdatesPage(nextPageToken, _))
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

  private def copyUpdatesPage(
      nextPageToken: Option[String],
      copyUpTo: CantonTimestamp,
  )(implicit tc: TraceContext): Next =
    progress.readUpdatesCursor.flatMap { cursor =>
      cursor.map(_.toTimestamp.timestamp) match {
        case Some(upTo) if upTo >= copyUpTo =>
          logger.info(
            s"Update objects are copied up to $upTo, where this Scan's own segments start"
          )
          next(CopySnapshots(None), UpdatesCopied(upTo))
        case copiedUpTo =>
          val from = copiedUpTo.getOrElse(CantonTimestamp.MinValue)
          listing
            .updateObjectsPage(from, copyUpTo, config.pageSize, nextPageToken)
            .flatMap { page =>
              if (page.objects.nonEmpty) copyPage(page)
              else {
                logger.debug(
                  s"The peers have no update objects after $from yet, waiting until they reach $copyUpTo"
                )
                waitThen(CopyUpdates(nextPageToken), WaitingForPeers(from))
              }
            }
      }
    }

  private def copyPage(page: BulkStorageObjects.UpdateObjectsPage)(implicit
      tc: TraceContext
  ): Next =
    for {
      _ <- copier.copy(page.objects)
      segments <- Future.fromTry(segmentsOf(page.objects))
      _ <- segments.foldLeft(Future.unit) { (acc, segment) =>
        acc.flatMap(_ => progress.persistUpdatesCursor(segment))
      }
      step <- next(
        CopyUpdates(page.nextPageToken),
        UpdatesPageCopied(page.objects.size, segments.lastOption),
      )
    } yield step

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
        case None => firstSnapshotTime(copyUpTo)
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
            case None =>
              nothingNewer(s"The peers have no committed snapshot at $ts")
            case Some(snapshot) if cursor.exists(_.timestamp >= snapshot.recordTime) =>
              nothingNewer(s"The peers have no snapshot after ${snapshot.recordTime}")
            case Some(snapshot) if snapshot.objects.isEmpty =>
              next(CopySnapshots(Some(snapshot.recordTime)), SnapshotSkipped(snapshot.recordTime))
            case Some(snapshot) =>
              val copied = TimestampWithMigrationId(snapshot.recordTime, currentMigrationId)
              for {
                _ <- copier.copy(snapshot.objects)
                _ <- progress.persistSnapshotsCursor(copied)
                step <- next(
                  CopySnapshots(Some(snapshot.recordTime)),
                  SnapshotCopied(copied, snapshot.objects.size),
                )
              } yield step
          }
      }
    } yield result

  private def firstSnapshotTime(end: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[CantonTimestamp]] =
    listing
      .updateObjectsPage(CantonTimestamp.MinValue, end, 1, None)
      .flatMap(page =>
        Future.fromTry(segmentsOf(page.objects)).map(_.headOption.map(_.toTimestamp.timestamp))
      )

  private def segmentsOf(objects: Seq[ObjectKeyAndChecksum]): scala.util.Try[Seq[UpdatesSegment]] =
    scala.util.Try {
      objects
        .map(_.key.takeWhile(_ != '/'))
        .distinct
        .map { folder =>
          storageConfig.getStartAndEndTimestampsForFolder(folder) match {
            case Right((from, to)) =>
              UpdatesSegment(
                TimestampWithMigrationId(from, currentMigrationId),
                TimestampWithMigrationId(to, currentMigrationId),
              )
            case Left(err) => throw new IllegalStateException(err)
          }
        }
        .sortBy(_.fromTimestamp)
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

  private sealed trait State
  private final case class CopyUpdates(nextPageToken: Option[String]) extends State
  private final case class CopySnapshots(lastRequested: Option[CantonTimestamp]) extends State
  private case object Finish extends State
  private case object Done extends State

  sealed trait Step
  final case class UpdatesPageCopied(objects: Int, lastSegment: Option[UpdatesSegment]) extends Step
  final case class UpdatesCopied(upTo: CantonTimestamp) extends Step
  final case class SnapshotSkipped(at: CantonTimestamp) extends Step
  final case class SnapshotCopied(snapshot: TimestampWithMigrationId, objects: Int) extends Step
  case object SnapshotsCopied extends Step
  final case class WaitingForPeers(at: CantonTimestamp) extends Step
  case object WaitingForHistoryStart extends Step
  case object NothingToCopy extends Step
  case object Completed extends Step
}

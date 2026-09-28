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
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.{PekkoRetryableService, PekkoRetryingService}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
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

  private[backfilling] def mksrc()(implicit tc: TraceContext): Source[Step, NotUsed] =
    Source.future(progress.isComplete).flatMapConcat {
      case true =>
        logger.info("Bulk storage backfilling from peers already complete, nothing to do")
        Source.empty
      case false =>
        Source.unfoldAsync[State, Step](CopyUpdates(None))(s => step(s))
    }

  private def step(state: State)(implicit tc: TraceContext): Future[Option[(State, Step)]] =
    state match {
      case CopyUpdates(nextPageToken) => copyUpdatesPage(nextPageToken)
      case CopySnapshots(lastRequested) => copyNextSnapshot(lastRequested)
      case Finish => progress.markComplete().flatMap(_ => next(Done, Completed))
      case Done => Future.successful[Option[(State, Step)]](None)
    }

  private def next(state: State, step: Step): Future[Option[(State, Step)]] =
    Future.successful(Some((state, step)))

  private def copyUpdatesPage(nextPageToken: Option[String])(implicit
      tc: TraceContext
  ): Future[Option[(State, Step)]] =
    for {
      cursor <- progress.readUpdatesCursor
      end <- upperBound.endRecordTime
      start = cursor.map(_.toTimestamp.timestamp).getOrElse(CantonTimestamp.MinValue)
      page <- listing.updateObjectsPage(start, end, config.pageSize, nextPageToken)
      result <-
        if (page.objects.isEmpty) {
          logger.info(s"No update objects after $start on the peers, updates are copied")
          next(CopySnapshots(None), UpdatesCopied(start))
        } else
          for {
            _ <- copier.copy(page.objects)
            segments <- Future.fromTry(segmentsOf(page.objects))
            _ <- segments.foldLeft(Future.unit) { (acc, segment) =>
              acc.flatMap(_ => progress.persistUpdatesCursor(segment))
            }
            nextState: State = page.nextPageToken match {
              case Some(_) => CopyUpdates(page.nextPageToken)
              case None => CopySnapshots(None)
            }
            step <- next(nextState, UpdatesPageCopied(page.objects.size, segments.lastOption))
          } yield step
    } yield result

  private def copyNextSnapshot(lastRequested: Option[CantonTimestamp])(implicit
      tc: TraceContext
  ): Future[Option[(State, Step)]] =
    for {
      cursor <- progress.readSnapshotsCursor
      end <- upperBound.endRecordTime
      requested <- (lastRequested, cursor) match {
        case (Some(last), _) =>
          Future.successful[Option[CantonTimestamp]](
            Some(storageConfig.computeBulkSnapshotTimeAfter(last))
          )
        case (None, Some(c)) =>
          Future.successful[Option[CantonTimestamp]](
            Some(storageConfig.computeBulkSnapshotTimeAfter(c.timestamp))
          )
        case (None, None) =>
          firstUpdateSegmentStart(end).map(_.map(storageConfig.computeBulkSnapshotTimeAtOrBefore))
      }
      result <- requested match {
        case None =>
          logger.info("The peers have no update objects, so there are no snapshots to copy")
          next(Finish, SnapshotsCopied)
        case Some(ts) if ts > end => next(Finish, SnapshotsCopied)
        case Some(ts) =>
          listing.snapshotObjectsAtOrBefore(ts).flatMap {
            case None =>
              logger.info("The peers have no committed snapshot yet, snapshots are copied")
              next(Finish, SnapshotsCopied)
            case Some(snapshot) if cursor.exists(_.timestamp >= snapshot.recordTime) =>
              logger.info(
                s"The peers have no snapshot after ${snapshot.recordTime}, snapshots are copied"
              )
              next(Finish, SnapshotsCopied)
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

  private def firstUpdateSegmentStart(end: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[CantonTimestamp]] =
    listing
      .updateObjectsPage(CantonTimestamp.MinValue, end, 1, None)
      .flatMap(page =>
        Future.fromTry(segmentsOf(page.objects)).map(_.headOption.map(_.fromTimestamp.timestamp))
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
        mksrc(),
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
  case object Completed extends Step
}

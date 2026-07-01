// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.MetricHandle
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.pattern.after
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.lfdecentralizedtrust.splice.PekkoRetryingService
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.{PageLimit, TimestampWithMigrationId, UpdateHistory}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

trait UpdateHistoryBulkStorageWriter {

  /** The main Flow that processes a given segment of updates.
    * The Flow must emit back the same segment as its output once processing is complete.
    */
  def processSegmentsFlow(implicit tc: TraceContext): Flow[UpdatesSegment, UpdatesSegment, NotUsed]
}

class UpdateHistoryBulkStoragePersistentProgress(
    kvStoreKey: String,
    kvProvider: ScanKeyValueProvider,
    metric: MetricHandle.Gauge[CantonTimestamp],
    override val loggerFactory: NamedLoggerFactory,
) extends NamedLogging
    with Spanning {

  import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider.updatesSegmentCodec

  def readLatestProcessedSegment(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[UpdatesSegment]] = {
    kvProvider.store.readValueAndLogOnDecodingFailure(kvStoreKey).value
  }

  def persistLatestProcessedSegment(segment: UpdatesSegment)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Unit] = {
    metric.updateValue(segment.toTimestamp.timestamp)
    kvProvider.store
      .setValue(kvStoreKey, segment)
      .map(_ => {
        logger.info(
          s"Successfully completed processing updates segment $segment, persisted as the latest processed segment in bulk storage"
        )
      })
  }
}

/** An abstract class for pipelines that process update history for bulk storage.
  */
class UpdateHistoryBulkStorage(
    description: String,
    writer: UpdateHistoryBulkStorageWriter,
    val persistentProgress: UpdateHistoryBulkStoragePersistentProgress,
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    updateHistory: UpdateHistory,
    currentMigrationId: Long,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends NamedLogging
    with Spanning {

  private def getMigrationIdForAcsSnapshot(
      snapshotTimestamp: CantonTimestamp
  )(implicit tc: TraceContext): Future[Long] = {
    /* The migration ID in ACS snapshots is always the lowest migration that has updates with a later record time,
       because we only create an ACS snapshot in an app if it has seen updates with a later timestamp.
       If no such updates exist, then we assume that the current migration will be that of the snapshot. If a migration
       happens before that time, then the app will restart with a higher migration, and therefore also restart dumping
       this segment.
     */
    updateHistory
      .getLowestMigrationForRecordTime(snapshotTimestamp)
      .map(_.getOrElse(currentMigrationId))
  }

  private def getSegmentEndAfter(
      ts: TimestampWithMigrationId
  )(implicit tc: TraceContext): Future[TimestampWithMigrationId] = {
    val endTs = storageConfig.computeBulkSnapshotTimeAfter(ts.timestamp)
    for {
      endMigration <-
        if (ts.migrationId < currentMigrationId) {
          getMigrationIdForAcsSnapshot(endTs)
        } else {
          /* When dumping updates for the current migration ID, we always assume that this migration ID
           continues beyond the segment, i.e. that the current migration ID is also the migration ID at
           the end of the segment. If this does not hold, and a migration happens before the end of the
           segment, then:
           a. this app will stop ingesting updates before the end of the segment, hence this segment will not be considered completed
           b. eventually, the app will be restarted with the new migration ID, and this segment will be retried in the new app,
              where (ts.migrationId == currentMigrationId) will no longer hold.
           */
          Future.successful(currentMigrationId)
        }
    } yield {
      TimestampWithMigrationId(endTs, endMigration)
    }
  }

  /** Gets the very first updates segment for this network after genesis
    * May return None if unknown yet. This could happen if no updates have been ingested,
    * so we do not know the genesis record time yet. The caller should then schedule a retry.
    */
  private def getFirstSegmentFromGenesis(implicit
      tc: TraceContext
  ): Future[Option[UpdatesSegment]] =
    for {
      firstUpdate <- updateHistory.getUpdatesWithoutImportUpdates(None, PageLimit.tryCreate(1))
      segmentEnd <- firstUpdate.headOption match {
        case None => Future.successful(None)
        case Some(first) =>
          getSegmentEndAfter(
            TimestampWithMigrationId(first.update.update.recordTime, first.migrationId)
          ).map(Some(_))
      }
    } yield {
      segmentEnd.map(UpdatesSegment(TimestampWithMigrationId(CantonTimestamp.MinValue, 0), _))
    }

  /** Gets the segment from which this app should start dumping, e.g. after a restart.
    * May return None if unknown yet. The caller should then sleep and retry.
    */
  private def getFirstSegment(implicit tc: TraceContext): Future[Option[UpdatesSegment]] = {
    persistentProgress.readLatestProcessedSegment.flatMap {
      case None => getFirstSegmentFromGenesis
      case Some(after) => getNextSegment(Some(after))
    }
  }

  private def getNextSegment(
      afterO: Option[UpdatesSegment]
  )(implicit tc: TraceContext): Future[Option[UpdatesSegment]] =
    afterO match {
      case Some(previous) =>
        getSegmentEndAfter(previous.toTimestamp).map(end =>
          Some(UpdatesSegment(previous.toTimestamp, end))
        )
      case None => getFirstSegment
    }

  private def mksrc()(implicit
      ec: ExecutionContext,
      actorSystem: org.apache.pekko.actor.ActorSystem,
      tc: TraceContext,
  ): Source[UpdatesSegment, Cancellable] = {

    // Wait for update history to initialize and for history backfilling to complete before starting bulk storage dumps
    val backfillingCompleteGate =
      Source
        .tick(0.seconds, appConfig.updatesPollingInterval.underlying, ())
        .mapAsync(1)(_ =>
          if (updateHistory.isReady)
            updateHistory.isHistoryBackfilled(currentMigrationId)
          else Future.successful(false)
        )
        .filter(identity)
        .take(1)

    backfillingCompleteGate.flatMap { _ =>
      Source
        .unfoldAsync(Option.empty[UpdatesSegment]) { current =>
          getNextSegment(current).flatMap {
            case Some(next) =>
              logger.info(s"Processing next updates segment: $next")
              Future.successful(Some((Some(next), Some(next))))
            case None =>
              logger.debug(s"Next segment after $current not known yet, scheduling next attempt...")
              after(appConfig.updatesPollingInterval.underlying, actorSystem.scheduler)(
                Future.successful(Some((current, None)))
              )
          }
        }
        .collect { case Some(segment) => segment }
        .via(writer.processSegmentsFlow)
        .mapAsync(1) { segment =>
          persistentProgress.persistLatestProcessedSegment(segment).map(_ => segment)
        }
    }
  }

  def asRetryableService(
      automationConfig: AutomationConfig,
      backoffClock: Clock,
      retryProvider: RetryProvider,
  )(implicit tracer: Tracer): PekkoRetryingService[UpdatesSegment] = {
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      val src = mksrc()
      new PekkoRetryingService(
        src,
        Sink.ignore,
        automationConfig,
        backoffClock,
        description,
        retryProvider,
        loggerFactory,
      )
    }
  }

}

object UpdateHistoryBulkStorage {
  case class UpdateHistoryObjectsResponse(
      objects: Seq[ObjectKeyAndChecksum],
      nextPageTokenO: Option[String],
  )
}

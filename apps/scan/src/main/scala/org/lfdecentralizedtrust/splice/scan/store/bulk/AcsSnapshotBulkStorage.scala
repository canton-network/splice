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
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.pattern.after
import org.lfdecentralizedtrust.splice.PekkoRetryingService
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.BulkStorageConfig
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.{TimestampWithMigrationId, UpdateHistory}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

/** An abstract class for pipelines that process ACS snapshots for bulk storage.
  */
abstract class AcsSnapshotBulkStorage(
    appConfig: BulkStorageConfig,
    acsSnapshotStore: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    kvProvider: ScanKeyValueProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends NamedLogging
    with Spanning {

  protected val description: String

  /** The key in the key-value store where the timestamp of the latest processed snapshot is stored. This is
    * used to resume processing from the correct point in case of restarts.
    */
  protected val kvStoreKey: String

  /** A metric that should be updated with the timestamp of the latest snapshot that was processed.
    */
  protected val processedTimestampMetric: MetricHandle.Gauge[CantonTimestamp]

  /** This method should return the timestamp of the next snapshot available, after `last`, if any.
    * The pipeline will  poll this method until it returns a new snapshot, and then process that snapshot
    * before polling for the next one. It is ok for this method to return a snapshot that should not actually
    * be processed, as the pipeline will call `shouldProcessSnapshotAt` to check if the snapshot should be
    * processed or skipped (but will remember that it was skipped and update `last` for the future calls accordingly).
    */
  protected def getNextSnapshotTimestampAfter(
      last: TimestampWithMigrationId
  )(implicit tc: TraceContext): Future[Option[TimestampWithMigrationId]]

  /** This method should return true if the snapshot at the given timestamp should be processed,
    * or false if it should be skipped. This is used to skip snapshots that are not relevant for bulk storage
    * (e.g. because they are in the DB, but are more frequent then the frequency we need for bulk storage).
    */
  protected def shouldProcessSnapshotAt(ts: TimestampWithMigrationId)(implicit
      tc: TraceContext
  ): Boolean

  /** This method should return the main Flow that processes the snapshot at the given timestamp.
    * It must emit back the same timestamp as its output once processing is complete.
    */
  protected def processSnapshotAt(ts: TimestampWithMigrationId)(implicit
      tc: TraceContext
  ): Flow[TimestampWithMigrationId, TimestampWithMigrationId, NotUsed]

  protected[bulk] def readLatestProcessedSnapshotTimestamp(implicit
      tc: TraceContext
  ): Future[Option[TimestampWithMigrationId]] = {
    import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider.acsSnapshotTimestampMigrationCodec
    kvProvider.store.readValueAndLogOnDecodingFailure(kvStoreKey).value
  }

  private def persistLatestProcessedSnapshotTimestamp(ts: TimestampWithMigrationId)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider.acsSnapshotTimestampMigrationCodec
    kvProvider.store
      .setValue(kvStoreKey, ts)
      .map(_ => {
        logger.info(
          s"Successfully completed processing snapshots from migration ${ts.migrationId}, timestamp ${ts.timestamp}"
        )
      })
  }

  private def getAcsSnapshotTimestampsAfter(
      start: TimestampWithMigrationId
  )(implicit tc: TraceContext): Source[TimestampWithMigrationId, NotUsed] = {
    Source
      .unfoldAsync(start) { (last: TimestampWithMigrationId) =>
        getNextSnapshotTimestampAfter(last).flatMap {
          case Some(snapshot) =>
            logger.info(
              s"next snapshot available, at migration ${snapshot.migrationId}, record time ${snapshot.timestamp}"
            )
            Future.successful(
              Some(
                (
                  snapshot,
                  Some(snapshot),
                )
              )
            )
          case None =>
            logger.debug("No new snapshot available, sleeping...")
            after(
              appConfig.snapshotPollingInterval.underlying,
              actorSystem.scheduler,
            ) {
              Future.successful(Some((last, None)))
            }
        }
      }
      .collect { case Some(ts) => ts }
  }

  /**  This is the main implementation of the pipeline. It is a Pekko Source that reads a `start` timestamp
    *   from the DB, and starts dumping to S3 all snapshots (strictly) after `start`. After every snapshot that
    *   is successfully dumped, it persists to the DB its timestamp, and emits that timestamp as an output.
    *   It is an infinite source that should never complete.
    */
  private def mksrc()(implicit tc: TraceContext): Source[TimestampWithMigrationId, Cancellable] = {

    // Wait for update history to initialize and for history backfilling to complete before starting bulk storage dumps
    val backfillingCompleteGate =
      Source
        .tick(0.seconds, appConfig.snapshotPollingInterval.underlying, ())
        .mapAsync(1)(_ =>
          if (updateHistory.isReady)
            updateHistory.isHistoryBackfilled(acsSnapshotStore.currentMigrationId)
          else Future.successful(false)
        )
        .filter(identity)
        .take(1)

    backfillingCompleteGate.flatMap { _ =>
      Source
        .future(readLatestProcessedSnapshotTimestamp)
        .flatMapConcat {
          case Some(start: TimestampWithMigrationId) =>
            logger.info(
              s"Latest processed snapshot was from migration ${start.migrationId}, timestamp ${start.timestamp}"
            )
            getAcsSnapshotTimestampsAfter(start)
          case None =>
            logger.info("No processed snapshots yet, starting from genesis")
            getAcsSnapshotTimestampsAfter(TimestampWithMigrationId(CantonTimestamp.MinValue, 0))
        }
        .filter { shouldProcessSnapshotAt }
        .flatMapConcat(ts =>
          Source
            .single(ts)
            .via(processSnapshotAt(ts))
        )
        .mapAsync(1) { ts =>
          processedTimestampMetric.updateValue(ts.timestamp)
          persistLatestProcessedSnapshotTimestamp(ts).map(_ => ts)
        }
    }
  }

  def asRetryableService(
      automationConfig: AutomationConfig,
      backoffClock: Clock,
      retryProvider: RetryProvider,
  )(implicit tracer: Tracer): PekkoRetryingService[TimestampWithMigrationId] = {
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

object AcsSnapshotBulkStorage {
  case class AcsSnapshotObjects(
      timestamp: CantonTimestamp,
      objects: Seq[ObjectKeyAndChecksum],
  )
}

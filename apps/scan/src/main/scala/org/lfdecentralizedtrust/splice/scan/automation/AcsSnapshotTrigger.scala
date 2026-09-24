// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory, Timer}
import com.daml.metrics.api.MetricQualification.{Errors, Latency, Traffic}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  AcsSnapshot,
  IncrementalAcsSnapshot,
  IncrementalAcsSnapshotTable,
}
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTrigger.AcsSnapshotsMetrics
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTriggerBase.{
  AcsSnapshotsMetricsBase,
  RetrieveTaskForMigrationResult,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig

import scala.concurrent.{ExecutionContext, Future}

class AcsSnapshotTrigger(
    store: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    storageConfig: ScanStorageConfig,
    metricsContext: MetricsContext,
    override protected val context: TriggerContext,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    mat: Materializer,
) extends AcsSnapshotTriggerBase(store, updateHistory, context) {

  override val snapshotTable: IncrementalAcsSnapshotTable =
    AcsSnapshotStore.IncrementalAcsSnapshotTable.Next

  override val snapshotMetrics: AcsSnapshotsMetricsBase = new AcsSnapshotsMetrics(
    context.metricsFactory
  )(metricsContext)

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[AcsSnapshotTriggerBase.Task]] = {
    if (!updateHistory.isReady) {
      logger.debug("Waiting for UpdateHistory to become ready.")
      Future.successful(Seq.empty)
    } else {
      AcsSnapshotTrigger
        .retrieveTaskForCurrentMigration(
          migrationId = store.currentMigrationId,
          isHistoryBackfilled = updateHistory.isHistoryBackfilled,
          getLastIngestedRecordTime = getLastIngestedRecordTime,
          getIncrementalSnapshot = () => getIncrementalSnapshot(),
          getLatestSnapshot = getLatestSnapshot,
          getMinRecordTime = getMinRecordTime,
          storageConfig = storageConfig,
          updateInterval = updateInterval,
          logger = logger,
        )
    }
  }

}

object AcsSnapshotTrigger {

  def retrieveTaskForCurrentMigration(
      migrationId: Long,
      isHistoryBackfilled: (Long) => Future[Boolean],
      getLastIngestedRecordTime: (Long) => Option[CantonTimestamp],
      getIncrementalSnapshot: () => Future[Option[IncrementalAcsSnapshot]],
      getLatestSnapshot: (Long) => Future[Option[AcsSnapshot]],
      getMinRecordTime: (Long) => Future[Option[CantonTimestamp]],
      storageConfig: ScanStorageConfig,
      updateInterval: java.time.Duration,
      logger: TracedLogger,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Seq[AcsSnapshotTriggerBase.Task]] = {
    AcsSnapshotTriggerBase
      .retrieveTaskForMigration(
        migrationId = migrationId,
        isHistoryBackfilled = isHistoryBackfilled,
        getIncrementalSnapshot = getIncrementalSnapshot,
        getLatestSnapshot = getLatestSnapshot,
        getMinRecordTime = getMinRecordTime,
        getMaxRecordTime = _ => Future.successful(Some(CantonTimestamp.MaxValue)),
        getLastIngestedRecordTime = getLastIngestedRecordTime,
        storageConfig = storageConfig,
        updateInterval = updateInterval,
        logger = logger,
      )
      .map {
        case RetrieveTaskForMigrationResult.Task(task) => Seq(task)
        case RetrieveTaskForMigrationResult.ReachedMigrationEnd => Seq.empty
        case RetrieveTaskForMigrationResult.Waiting => Seq.empty
      }
  }

  class AcsSnapshotsMetrics(metricsFactory: LabeledMetricsFactory)(implicit
      metricsContext: MetricsContext
  ) extends AcsSnapshotsMetricsBase {
    private val acsSnapshotsPrefix: MetricName =
      SpliceMetrics.MetricsHistoryPrefix :+ "acs-snapshots"

    override lazy val latestRecordTimeSave: Gauge[CantonTimestamp] =
      SpliceMetrics.cantonTimestampGauge(
        metricsFactory,
        MetricInfo(
          name = acsSnapshotsPrefix :+ "latest-record-time-save",
          summary = "The record time of the latest acs snapshot",
          Traffic,
        ),
        initial = CantonTimestamp.MinValue,
      )(metricsContext)

    override lazy val latestRecordTimeUpdate: Gauge[CantonTimestamp] =
      SpliceMetrics.cantonTimestampGauge(
        metricsFactory,
        MetricInfo(
          name = acsSnapshotsPrefix :+ "latest-record-time-update",
          summary = "The record time of the latest incremental acs snapshot",
          Traffic,
        ),
        initial = CantonTimestamp.MinValue,
      )(metricsContext)

    override lazy val latencyUpdate: Timer =
      metricsFactory.timer(
        MetricInfo(
          name = acsSnapshotsPrefix :+ "latency-update",
          summary = "How long it takes to update an incremental snapshot",
          qualification = Latency,
        )
      )(metricsContext)

    override lazy val latencySave: Timer =
      metricsFactory.timer(
        MetricInfo(
          name = acsSnapshotsPrefix :+ "latency-save",
          summary = "How long it takes to save an incremental snapshot",
          qualification = Latency,
        )
      )(metricsContext)

    override lazy val waitingForLock: Gauge[Int] = metricsFactory.gauge(
      MetricInfo(
        name = acsSnapshotsPrefix :+ "waiting-for-lock",
        summary =
          "Whether the last acs snapshot task had to be skipped because it could not acquire a lock",
        qualification = Errors,
      ),
      -1,
    )(metricsContext)

    override lazy val snapshotSize: Gauge[Int] = metricsFactory.gauge(
      MetricInfo(
        name = acsSnapshotsPrefix :+ "snapshot-size",
        summary = "Number of rows copied in the latest acs snapshot",
        Traffic,
      ),
      0,
    )(metricsContext)
  }
}

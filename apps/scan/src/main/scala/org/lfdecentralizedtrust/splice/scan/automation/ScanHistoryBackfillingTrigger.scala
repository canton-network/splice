// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.metrics.api.MetricHandle.{Counter, Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.{Debug, Traffic}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskNoop,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.config.UpgradesConfig
import org.lfdecentralizedtrust.splice.environment.{SpliceLedgerClient, SpliceMetrics}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BackfillingScanConnection
import org.lfdecentralizedtrust.splice.scan.store.ScanHistoryBackfilling.{
  FoundingTransactionTreeUpdate,
  InitialTransactionTreeUpdate,
  JoiningTransactionTreeUpdate,
}
import org.lfdecentralizedtrust.splice.scan.store.{ScanHistoryBackfilling, ScanStore}
import org.lfdecentralizedtrust.splice.store.{
  HistoryBackfilling,
  ImportUpdatesBackfilling,
  PageLimit,
  TimestampWithMigrationId,
  TreeUpdateWithMigrationId,
  UpdateHistory,
}
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, LifeCycle, SyncCloseable}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.scan.automation.ScanHistoryBackfillingTrigger.{
  ImportUpdatesBackfillingMetrics,
  UpdateHistoryBackfillingMetrics,
}
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingState

import scala.concurrent.{ExecutionContextExecutor, Future, blocking}

class ScanHistoryBackfillingTrigger(
    store: ScanStore,
    updateHistory: UpdateHistory,
    svName: String,
    ledgerClient: SpliceLedgerClient,
    batchSize: Int,
    importUpdateBackfillingEnabled: Boolean,
    svParty: PartyId,
    upgradesConfig: UpgradesConfig,
    metricsContext: MetricsContext,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContextExecutor,
    override val tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[ScanHistoryBackfillingTrigger.Task] {

  private val currentMigrationId = updateHistory.domainMigrationId

  private val scanConnection = new PeerBftScanConnection(
    store,
    svName,
    ledgerClient,
    context.config,
    upgradesConfig,
    context.clock,
    context.retryProvider,
    loggerFactory,
  )

  private val updateHistoryBackfillingMetrics = new UpdateHistoryBackfillingMetrics(
    context.metricsFactory
  )(metricsContext)
  private val importUpdatesBackfillingMetrics = new ImportUpdatesBackfillingMetrics(
    context.metricsFactory
  )(metricsContext)

  /** A cursor for iterating over the beginning of the update history in findHistoryStart,
    *  see [[org.lfdecentralizedtrust.splice.updateHistory.getUpdates()]].
    *  We need to store this as we don't want to start over from the beginning every time the trigger runs.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var findHistoryStartAfter: Option[TimestampWithMigrationId] = None

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var backfillingVar: Option[ScanHistoryBackfilling] = None

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ScanHistoryBackfillingTrigger.Task]] = {
    if (!updateHistory.isReady) {
      logger.debug("UpdateHistory is not yet ready")
      Future.successful(Seq.empty)
    } else if (importUpdateBackfillingEnabled && !updateHistory.corruptAcsSnapshotsDeleted) {
      logger.debug("There may be corrupt ACS snapshots that need to be deleted")
      Future.successful(Seq.empty)
    } else {
      updateHistory.getBackfillingState().map {
        case BackfillingState.Complete =>
          updateHistoryBackfillingMetrics.completed.updateValue(1)
          importUpdatesBackfillingMetrics.completed.updateValue(1)
          Seq.empty
        case BackfillingState.InProgress(updatesComplete, _) =>
          if (!updatesComplete) {
            importUpdatesBackfillingMetrics.completed.updateValue(0)
            Seq(ScanHistoryBackfillingTrigger.BackfillTask())
          } else {
            updateHistoryBackfillingMetrics.completed.updateValue(1)
            Seq(ScanHistoryBackfillingTrigger.ImportUpdatesBackfillTask())
          }
        case BackfillingState.NotInitialized =>
          updateHistoryBackfillingMetrics.completed.updateValue(0)
          importUpdatesBackfillingMetrics.completed.updateValue(0)
          Seq(ScanHistoryBackfillingTrigger.InitializeBackfillingTask(findHistoryStartAfter))
      }
    }
  }

  override protected def isStaleTask(task: ScanHistoryBackfillingTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  override protected def completeTask(task: ScanHistoryBackfillingTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = task match {
    case ScanHistoryBackfillingTrigger.InitializeBackfillingTask(_) =>
      initializeBackfilling()
    case ScanHistoryBackfillingTrigger.ImportUpdatesBackfillTask() =>
      if (!importUpdateBackfillingEnabled) {
        throw new RuntimeException(
          "Import updates backfilling is disabled, this place should not be reached"
        )
      }
      performImportUpdatesBackfilling()
    case ScanHistoryBackfillingTrigger.BackfillTask() =>
      performBackfilling()
  }

  private def initializeBackfillingFromUpdates(updates: Seq[TreeUpdateWithMigrationId])(implicit
      traceContext: TraceContext
  ) = {
    val initialUpdateO = updates.collectFirst(
      InitialTransactionTreeUpdate.fromTreeUpdate(
        dsoParty = store.key.dsoParty,
        svParty = svParty,
      )
    )
    for {
      result <- initialUpdateO match {
        case Some(FoundingTransactionTreeUpdate(treeUpdate, _)) =>
          for {
            _ <- updateHistory
              .initializeBackfilling(
                treeUpdate.migrationId,
                treeUpdate.update.synchronizerId,
                treeUpdate.update.update.updateId,
                complete = true,
              )
          } yield TaskSuccess(
            s"Initialized backfilling from founding update ${treeUpdate.update.update.updateId}"
          )
        case Some(JoiningTransactionTreeUpdate(treeUpdate, _)) =>
          for {
            // Before deleting updates, we need to delete ACS snapshots that were generated before backfilling was enabled.
            // This will delete all ACS snapshots for migration id where the SV node joined the network.
            _ <- updateHistory.deleteAcsSnapshotsAfter(
              historyId = updateHistory.historyId,
              migrationId = treeUpdate.migrationId,
              recordTime = CantonTimestamp.MinValue,
            )
            // Joining SVs need to delete updates before the joining transaction, because they ingested those updates
            // only with the visibility of the SV party and not the DSO party.
            // Note that this will also delete the import updates because they have a record time of 0,
            // which is good because we want to remove them.
            _ <- updateHistory.deleteUpdatesBefore(
              synchronizerId = treeUpdate.update.synchronizerId,
              migrationId = treeUpdate.migrationId,
              recordTime = treeUpdate.update.update.recordTime,
            )
            _ <- updateHistory
              .initializeBackfilling(
                treeUpdate.migrationId,
                treeUpdate.update.synchronizerId,
                treeUpdate.update.update.updateId,
                complete = false,
              )
          } yield TaskSuccess(
            s"Initialized backfilling from joining update ${treeUpdate.update.update.updateId}"
          )
        case None =>
          Future.successful(
            TaskSuccess(
              s"No founding or joining transaction found until ${updates.lastOption.map(_.update.update.recordTime)}"
            )
          )
      }
    } yield result
  }

  private def initializeBackfilling()(implicit
      traceContext: TraceContext
  ): Future[TaskOutcome] = blocking {
    mutex.exclusive {
      val batchSize = 100
      for {
        updates <- updateHistory.getUpdatesWithoutImportUpdates(
          findHistoryStartAfter,
          PageLimit.tryCreate(batchSize),
        )
        _ = updates.lastOption.foreach(u =>
          findHistoryStartAfter =
            Some(TimestampWithMigrationId(u.update.update.recordTime, u.migrationId))
        )
        result <-
          if (updates.isEmpty) {
            Future.successful(TaskNoop)
          } else {
            initializeBackfillingFromUpdates(updates)
          }
      } yield result
    }
  }

  private def getOrCreateBackfilling(
      connection: BackfillingScanConnection
  ): ScanHistoryBackfilling = blocking {
    mutex.exclusive {
      backfillingVar match {
        case Some(backfilling) =>
          backfilling
        case None =>
          val backfilling =
            new ScanHistoryBackfilling(
              connection = connection,
              destinationHistory = updateHistory.destinationHistory,
              currentMigrationId = currentMigrationId,
              batchSize = batchSize,
              loggerFactory = loggerFactory,
            )
          backfillingVar = Some(backfilling)
          backfilling
      }
    }
  }

  private def performBackfilling()(implicit traceContext: TraceContext): Future[TaskOutcome] = for {
    connection <- scanConnection.connection
    backfilling = getOrCreateBackfilling(connection)
    outcome <- backfilling.backfill().map {
      case HistoryBackfilling.Outcome.MoreWorkAvailableNow(workDone) =>
        updateHistoryBackfillingMetrics.completed.updateValue(0)
        // Using MetricsContext.Empty is okay, because it's merged with the StoreMetrics context
        updateHistoryBackfillingMetrics.latestRecordTime.updateValue(
          workDone.lastBackfilledRecordTime
        )(MetricsContext.Empty)
        updateHistoryBackfillingMetrics.updateCount.inc(
          workDone.backfilledUpdates
        )(MetricsContext.Empty)
        updateHistoryBackfillingMetrics.eventCount.inc(workDone.backfilledCreatedEvents)(
          MetricsContext("event_type" -> "created")
        )
        updateHistoryBackfillingMetrics.eventCount.inc(workDone.backfilledExercisedEvents)(
          MetricsContext("event_type" -> "exercised")
        )
        TaskSuccess("Backfilling step completed")
      case HistoryBackfilling.Outcome.MoreWorkAvailableLater =>
        updateHistoryBackfillingMetrics.completed.updateValue(0)
        TaskNoop
      case HistoryBackfilling.Outcome.BackfillingIsComplete =>
        updateHistoryBackfillingMetrics.completed.updateValue(1)
        logger.info("UpdateHistory backfilling is complete")
        TaskSuccess("Backfilling completed")
    }
  } yield outcome

  private def performImportUpdatesBackfilling()(implicit
      traceContext: TraceContext
  ): Future[TaskOutcome] = for {
    connection <- scanConnection.connection
    backfilling = getOrCreateBackfilling(connection)
    outcome <- backfilling.backfillImportUpdates().map {
      case ImportUpdatesBackfilling.Outcome.MoreWorkAvailableNow(workDone) =>
        importUpdatesBackfillingMetrics.completed.updateValue(0)
        // Using MetricsContext.Empty is okay, because it's merged with the StoreMetrics context
        importUpdatesBackfillingMetrics.contractCount.inc(
          workDone.backfilledContracts
        )(MetricsContext.Empty)
        importUpdatesBackfillingMetrics.latestMigrationId.updateValue(workDone.migrationId)
        TaskSuccess("Backfilling import updates step completed")
      case ImportUpdatesBackfilling.Outcome.MoreWorkAvailableLater =>
        importUpdatesBackfillingMetrics.completed.updateValue(0)
        TaskNoop
      case ImportUpdatesBackfilling.Outcome.BackfillingIsComplete =>
        importUpdatesBackfillingMetrics.completed.updateValue(1)
        logger.info("UpdateHistory backfilling import updates is complete")
        TaskSuccess("Backfilling import updates completed")
    }
  } yield outcome

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    LifeCycle.close(scanConnection)(logger)
    super.closeAsync() :+
      SyncCloseable(
        "scan_history_backfilling_trigger_update_history_backfilling_metrics",
        LifeCycle.close(updateHistoryBackfillingMetrics)(logger),
      ) :+
      SyncCloseable(
        "scan_history_backfilling_trigger_import_updates_backfilling_metrics",
        LifeCycle.close(importUpdatesBackfillingMetrics)(logger),
      )
  }
}

object ScanHistoryBackfillingTrigger {
  sealed trait Task extends PrettyPrinting
  final case class InitializeBackfillingTask(
      after: Option[TimestampWithMigrationId]
  ) extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("after", _.after))
  }
  final case class BackfillTask() extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass()
  }
  final case class ImportUpdatesBackfillTask() extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass()
  }

  class UpdateHistoryBackfillingMetrics(metricsFactory: LabeledMetricsFactory)(implicit
      metricsContext: MetricsContext
  ) extends AutoCloseable {

    private val historyBackfillingPrefix: MetricName =
      SpliceMetrics.MetricsHistoryPrefix :+ "backfilling"

    lazy val latestRecordTime = SpliceMetrics.cantonTimestampGauge(
      metricsFactory,
      MetricInfo(
        name = historyBackfillingPrefix :+ "latest-record-time",
        summary = "The latest record time that has been backfilled",
        Traffic,
      ),
      initial = CantonTimestamp.MinValue,
    )(metricsContext)

    val updateCount: Counter =
      metricsFactory.counter(
        MetricInfo(
          name = historyBackfillingPrefix :+ "transaction-count",
          summary = "The number of updates (txs & reassignments) that have been backfilled",
          Traffic,
        )
      )(metricsContext)

    val eventCount: Counter =
      metricsFactory.counter(
        MetricInfo(
          name = historyBackfillingPrefix :+ "event-count",
          summary = "The number of events that have been backfilled",
          Traffic,
        )
      )(metricsContext)

    lazy val completed: Gauge[Int] =
      metricsFactory.gauge(
        MetricInfo(
          name = historyBackfillingPrefix :+ "completed",
          summary = "Whether it was completed (1) or not (0)",
          Debug,
        ),
        initial = 0,
      )(metricsContext)

    override def close(): Unit = {
      latestRecordTime.close()
      completed.close()
    }
  }

  class ImportUpdatesBackfillingMetrics(metricsFactory: LabeledMetricsFactory)(implicit
      metricsContext: MetricsContext
  ) extends AutoCloseable {

    private val importUpdatesBackfillingPrefix: MetricName =
      SpliceMetrics.MetricsHistoryPrefix :+ "import-updates-backfilling"

    lazy val latestMigrationId: Gauge[Long] =
      metricsFactory.gauge(
        MetricInfo(
          name = importUpdatesBackfillingPrefix :+ "latest-migration-id",
          summary = "The migration id of the latest backfilled import update",
          Traffic,
        ),
        initial = -1L,
      )(metricsContext)

    val contractCount: Counter =
      metricsFactory.counter(
        MetricInfo(
          name = importUpdatesBackfillingPrefix :+ "contract-count",
          summary = "The number of contracts that have been backfilled",
          Traffic,
        )
      )(metricsContext)

    lazy val completed: Gauge[Int] =
      metricsFactory.gauge(
        MetricInfo(
          name = importUpdatesBackfillingPrefix :+ "completed",
          summary = "Whether it was completed (1) or not (0)",
          Debug,
        ),
        initial = 0,
      )(metricsContext)

    override def close(): Unit = {
      latestMigrationId.close()
      completed.close()
    }
  }
}

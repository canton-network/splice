// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.MetricHandle.{Counter, Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.{Debug, Traffic}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, LifeCycle, SyncCloseable}
import org.lfdecentralizedtrust.splice.store.{
  HistoryBackfilling,
  TxLogAppStore,
  TxLogBackfilling,
  UpdateHistory,
}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.TxLogBackfillingTrigger.TxLogBackfillingMetrics
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.TxLogBackfillingState
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingState

import scala.concurrent.{ExecutionContext, Future}

class TxLogBackfillingTrigger[TXE](
    store: TxLogAppStore[TXE],
    updateHistory: UpdateHistory,
    batchSize: Int,
    metricsContext: MetricsContext,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[TxLogBackfillingTrigger.Task] {

  private def party: PartyId = updateHistory.updateStreamParty

  override protected def extraMetricLabels: Seq[(String, String)] = Seq(
    "party" -> party.toProtoPrimitive
  )

  private val historyMetrics = new TxLogBackfillingMetrics(context.metricsFactory)(metricsContext)

  private val backfilling = new TxLogBackfilling(
    store.multiDomainAcsStore,
    updateHistory,
    batchSize,
    context.loggerFactory,
  )

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[TxLogBackfillingTrigger.Task]] = {
    if (!updateHistory.isReady) {
      logger.debug("UpdateHistory is not yet ready")
      Future.successful(Seq.empty)
    } else if (!store.multiDomainAcsStore.destinationHistory.isReady) {
      logger.debug("MultiDomainAcsStore is not yet ready")
      Future.successful(Seq.empty)
    } else {
      for {
        sourceState <- updateHistory.getBackfillingState()
        destinationState <- store.multiDomainAcsStore.getTxLogBackfillingState()
      } yield {
        sourceState match {
          case BackfillingState.Complete =>
            destinationState match {
              case TxLogBackfillingState.Complete =>
                historyMetrics.completed.updateValue(1)
                Seq.empty
              case TxLogBackfillingState.InProgress =>
                Seq(TxLogBackfillingTrigger.BackfillTask(party))
              case TxLogBackfillingState.NotInitialized =>
                Seq(TxLogBackfillingTrigger.InitializeBackfillingTask(party))
            }
          case _ =>
            logger.debug("UpdateHistory is not yet complete")
            historyMetrics.completed.updateValue(0)
            Seq.empty
        }
      }
    }
  }

  override protected def isStaleTask(task: TxLogBackfillingTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  override protected def completeTask(task: TxLogBackfillingTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = task match {
    case TxLogBackfillingTrigger.BackfillTask(_) =>
      performBackfilling()
    case TxLogBackfillingTrigger.InitializeBackfillingTask(_) =>
      initializeBackfilling()
  }

  private def initializeBackfilling()(implicit
      traceContext: TraceContext
  ): Future[TaskOutcome] = {
    logger.info("Initializing backfilling")
    store.multiDomainAcsStore.initializeTxLogBackfilling().map { _ =>
      TaskSuccess("Backfilling initialized")
    }
  }

  private def performBackfilling()(implicit traceContext: TraceContext): Future[TaskOutcome] = for {
    outcome <- backfilling.backfill().map {
      case HistoryBackfilling.Outcome.MoreWorkAvailableNow(workDone) =>
        historyMetrics.completed.updateValue(0)
        historyMetrics.latestRecordTime.updateValue(
          workDone.lastBackfilledRecordTime
        )(MetricsContext.Empty)
        historyMetrics.updateCount.inc(
          workDone.backfilledUpdates
        )(MetricsContext.Empty)
        historyMetrics.eventCount.inc(workDone.backfilledCreatedEvents)(
          MetricsContext("event_type" -> "created")
        )
        historyMetrics.eventCount.inc(workDone.backfilledExercisedEvents)(
          MetricsContext("event_type" -> "exercised")
        )
        TaskSuccess("Backfilling step completed")
      case HistoryBackfilling.Outcome.MoreWorkAvailableLater =>
        historyMetrics.completed.updateValue(0)
        TaskNoop
      case HistoryBackfilling.Outcome.BackfillingIsComplete =>
        historyMetrics.completed.updateValue(1)
        logger.info(
          "TxLog backfilling is complete, this trigger should not do any work ever again"
        )
        TaskSuccess("Backfilling completed")
    }
  } yield outcome

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync() :+
      SyncCloseable("txlog_backfilling_metrics", LifeCycle.close(historyMetrics)(logger))
}

object TxLogBackfillingTrigger {
  sealed trait Task extends PrettyPrinting
  final case class BackfillTask(party: PartyId) extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("party", _.party)
      )
  }
  final case class InitializeBackfillingTask(party: PartyId) extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("party", _.party)
      )
  }

  class TxLogBackfillingMetrics(metricsFactory: LabeledMetricsFactory)(implicit
      metricsContext: MetricsContext
  ) extends AutoCloseable {

    private val historyBackfillingPrefix: MetricName =
      SpliceMetrics.MetricsHistoryPrefix :+ "txlog-backfilling"

    val latestRecordTime: Gauge[CantonTimestamp] =
      SpliceMetrics.cantonTimestampGauge(
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
}

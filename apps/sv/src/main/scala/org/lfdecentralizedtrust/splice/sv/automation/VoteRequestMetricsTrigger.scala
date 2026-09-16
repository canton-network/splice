// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Saturation
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.VoteRequest
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.sv.automation.VoteRequestMetricsTrigger.{
  VoteRequestMetrics,
  countByState,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.Contract

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class VoteRequestMetricsTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    val mat: Materializer,
) extends PollingTrigger {

  private val voteRequestMetrics = new VoteRequestMetrics(context.metricsFactory)
  private val svParty = dsoStore.key.svParty.toProtoPrimitive

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      voteRequests <- dsoStore.listVoteRequests()
      readyToCloseContracts <- dsoStore.listVoteRequestsReadyToBeClosed(
        context.clock.now,
        PageLimit.Max,
      )(traceContext)
    } yield {
      val counts = countByState(
        voteRequests,
        readyToCloseContracts.map(_.contractId).toSet,
        svParty,
      )
      voteRequestMetrics.actionNeeded.updateValue(counts.actionNeeded)
      voteRequestMetrics.inProgress.updateValue(counts.inProgress)
      voteRequestMetrics.readyToClose.updateValue(counts.readyToClose)
      false
    }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = super
    .closeAsync()
    .appended(SyncCloseable("vote request metrics", voteRequestMetrics.close()))
}

object VoteRequestMetricsTrigger {

  case class VoteRequestCounts(actionNeeded: Long, inProgress: Long, readyToClose: Long)

  def countByState(
      voteRequests: Seq[Contract[VoteRequest.ContractId, VoteRequest]],
      readyToCloseCids: Set[VoteRequest.ContractId],
      svParty: String,
  ): VoteRequestCounts = {
    val (readyToClose, open) =
      voteRequests.partition(request => readyToCloseCids.contains(request.contractId))
    val (inProgress, actionNeeded) =
      open.partition(_.payload.votes.values().asScala.exists(_.sv == svParty))
    VoteRequestCounts(
      actionNeeded = actionNeeded.size.toLong,
      inProgress = inProgress.size.toLong,
      readyToClose = readyToClose.size.toLong,
    )
  }

  case class VoteRequestMetrics(metricsFactory: LabeledMetricsFactory) extends AutoCloseable {

    private val name: MetricName =
      SpliceMetrics.MetricsPrefix :+ "sv_vote_requests" :+ "active"

    private def stateGauge(state: String): Gauge[Long] =
      metricsFactory.gauge(
        MetricInfo(
          name,
          "The number of active vote requests, split by their state relative to this SV",
          Saturation,
          "The state label is one of: " +
            "action_needed (the request is open for voting and this SV has not voted yet), " +
            "in_progress (the request is open for voting and this SV has voted), " +
            "ready_to_close (the request fulfills the conditions for the closing automation " +
            "to close it, e.g. its voting deadline has passed).",
        ),
        0L,
      )(MetricsContext.Empty.withExtraLabels("state" -> state))

    val actionNeeded: Gauge[Long] = stateGauge("action_needed")
    val inProgress: Gauge[Long] = stateGauge("in_progress")
    val readyToClose: Gauge[Long] = stateGauge("ready_to_close")

    override def close(): Unit = {
      actionNeeded.close()
      inProgress.close()
      readyToClose.close()
    }
  }
}

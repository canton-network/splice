// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import cats.implicits.{catsSyntaxOptionId, showInterpolator}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{SequencerId, SynchronizerId}
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.transaction.TopologyChangeOp
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
  TriggerEnabledSynchronization,
}
import org.lfdecentralizedtrust.splice.environment.SynchronizerNode.LocalSynchronizerNodes
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.lsu.LsuCancellationTrigger.LsuCancellationTask
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler.SynchronizerNodeState.OnboardedImmediately

import scala.concurrent.{ExecutionContext, Future}

class LsuCancellationTrigger(
    baseContext: TriggerContext,
    reconciler: SynchronizerNodeReconciler,
    localSynchronizerNodes: LocalSynchronizerNodes[LocalSynchronizerNode],
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[LsuCancellationTask] {

  private val currentSynchronizerNode = localSynchronizerNodes.current

  override protected lazy val context: TriggerContext =
    baseContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)

  protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[LsuCancellationTask]] = {
    for {
      physicalSynchronizerId <- currentSynchronizerNode.sequencerAdminConnection
        .getPhysicalSynchronizerId()
      sequencerId <- currentSynchronizerNode.sequencerAdminConnection.getSequencerId
      successor <- currentSynchronizerNode.sequencerAdminConnection
        .lookupSequencerSuccessors(physicalSynchronizerId.logical, sequencerId)
      removedAnnouncement <- currentSynchronizerNode.sequencerAdminConnection
        .lookupSynchronizerLsuAnnouncement(
          physicalSynchronizerId.logical,
          TimeQuery.HeadState,
          TopologyTransactionType.AuthorizedState,
          operation = Some(TopologyChangeOp.Remove),
        )
    } yield {
      if (removedAnnouncement.isDefined && successor.isDefined) {
        Seq(LsuCancellationTask(physicalSynchronizerId.logical, sequencerId))
      } else {
        Seq.empty
      }
    }
  }

  protected def completeTask(task: ScheduledTaskTrigger.ReadyTask[LsuCancellationTask])(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val synchronizerId = task.work.synchronizerId
    for {
      _ <- reconciler.reconcileSynchronizerNodeConfigIfRequired(
        localSynchronizerNodes.copy(successor = None).some,
        synchronizerId,
        OnboardedImmediately,
      )
      _ <- currentSynchronizerNode.sequencerAdminConnection
        .removeSequencerSuccessor(synchronizerId, task.work.sequencerId)
    } yield {
      TaskSuccess(
        show"Cancelled logical synchronizer upgrade for $synchronizerId"
      )
    }
  }

  protected def isStaleTask(task: ScheduledTaskTrigger.ReadyTask[LsuCancellationTask])(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)
}

object LsuCancellationTrigger {
  case class LsuCancellationTask(synchronizerId: SynchronizerId, sequencerId: SequencerId)
      extends PrettyPrinting {

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("synchronizerId", _.synchronizerId),
      param("sequencerId", _.sequencerId),
    )
  }
}

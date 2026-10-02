// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.automation

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.{MediatorId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{GrantUnlimitedTrafficTriggerBase, TriggerContext}
import org.lfdecentralizedtrust.splice.automation.GrantUnlimitedTrafficTriggerBase.{
  Task,
  UnlimitedTraffic,
}
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologySnapshot
import org.lfdecentralizedtrust.splice.environment.SynchronizerNodeService
import org.lfdecentralizedtrust.splice.syncoperator.SyncOperatorSynchronizerNode

import scala.concurrent.{ExecutionContext, Future}

/** Grants unlimited traffic to this synchronizer's mediators on its sequencer. Mediators have no
  * purchase path, so once the base rate is zero they could not send verdicts otherwise.
  */
class MediatorUnlimitedTrafficTrigger(
    override protected val context: TriggerContext,
    synchronizerId: SynchronizerId,
    synchronizerNodeService: SynchronizerNodeService[SyncOperatorSynchronizerNode],
    trafficBalanceReconciliationDelay: NonNegativeFiniteDuration,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends GrantUnlimitedTrafficTriggerBase(trafficBalanceReconciliationDelay) {

  override protected def sequencerAdminConnection()(implicit
      tc: TraceContext
  ): Future[SequencerAdminConnection] =
    synchronizerNodeService.sequencerAdminConnection()

  override protected def isActiveMember(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    sequencerAdminConnection()
      .flatMap(activeMediators(_, task.synchronizerId))
      .map(_.contains(task.memberId))

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    for {
      connection <- sequencerAdminConnection()
      mediators <- activeMediators(connection, synchronizerId)
      trafficStates <- connection.listSequencerTrafficControlState(mediators)
    } yield {
      val limitByMember = trafficStates.map(state => state.member -> state.extraTrafficLimit).toMap
      mediators.collect {
        case mediatorId if limitByMember.get(mediatorId).exists(_ != UnlimitedTraffic) =>
          Task(synchronizerId, mediatorId)
      }
    }
  }

  private def activeMediators(
      connection: SequencerAdminConnection,
      synchronizer: SynchronizerId,
  )(implicit
      tc: TraceContext
  ): Future[Seq[MediatorId]] =
    connection
      .getMediatorSynchronizerState(
        synchronizer,
        topologySnapshot = TopologySnapshot.Effective,
      )
      .map(_.mapping.active.forgetNE)
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.automation

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.DynamicSynchronizerParameters
import com.digitalasset.canton.sequencing.TrafficControlParameters
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.syncoperator.automation.ReconcileDedicatedSynchronizerParametersTrigger.Task
import org.lfdecentralizedtrust.splice.syncoperator.store.SyncOperatorStore
import org.lfdecentralizedtrust.splice.environment.SynchronizerNodeService
import org.lfdecentralizedtrust.splice.syncoperator.SyncOperatorSynchronizerNode

import scala.concurrent.{ExecutionContext, Future}

/** Reconciles the dynamic parameters of the dedicated synchronizer this operator serves. */
class ReconcileDedicatedSynchronizerParametersTrigger(
    override protected val context: TriggerContext,
    store: SyncOperatorStore,
    synchronizerNodeService: SynchronizerNodeService[SyncOperatorSynchronizerNode],
    trafficControl: TrafficControlParameters,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task] {

  private val synchronizerId = store.key.synchronizerId

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] =
    for {
      connection <- synchronizerNodeService.sequencerAdminConnection()
      reconciled <- isReconciled(connection)
    } yield if (reconciled) Seq.empty else Seq(Task(synchronizerId))

  override protected def completeTask(task: Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    synchronizerNodeService
      .sequencerAdminConnection()
      .flatMap(_.ensureDomainParameters(task.synchronizerId, withTrafficControl))
      .map(_ =>
        TaskSuccess(
          s"Set the traffic control parameters on ${task.synchronizerId}, " +
            s"base traffic amount ${trafficControl.maxBaseTrafficAmount}"
        )
      )

  override protected def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    synchronizerNodeService.sequencerAdminConnection().flatMap(isReconciled)

  private def isReconciled(
      connection: SequencerAdminConnection
  )(implicit tc: TraceContext): Future[Boolean] =
    connection
      .getSynchronizerParametersState(synchronizerId)
      .map(state => state.mapping.parameters == withTrafficControl(state.mapping.parameters))

  /** Applies the configured parameters, leaving the rest as the synchronizer has them */
  private def withTrafficControl(
      parameters: DynamicSynchronizerParameters
  ): DynamicSynchronizerParameters =
    parameters.trafficControl.fold(parameters)(current =>
      parameters.tryUpdate(trafficControlParameters =
        Some(
          current.copy(
            maxBaseTrafficAmount = trafficControl.maxBaseTrafficAmount,
            readVsWriteScalingFactor = trafficControl.readVsWriteScalingFactor,
            maxBaseTrafficAccumulationDuration = trafficControl.maxBaseTrafficAccumulationDuration,
            freeConfirmationResponses = trafficControl.freeConfirmationResponses,
          )
        )
      )
    )
}

object ReconcileDedicatedSynchronizerParametersTrigger {

  final case class Task(synchronizerId: SynchronizerId) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("synchronizerId", _.synchronizerId))
  }
}

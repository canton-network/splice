// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.automation

import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.DynamicSynchronizerParameters
import com.digitalasset.canton.time.PositiveFiniteDuration
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
import org.lfdecentralizedtrust.splice.environment.{
  SequencerAdminConnection,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.syncoperator.automation.ReconcileDedicatedSynchronizerParametersTrigger.Task
import org.lfdecentralizedtrust.splice.syncoperator.SyncOperatorSynchronizerNode
import org.lfdecentralizedtrust.splice.syncoperator.config.SyncOperatorTrafficControlConfig

import scala.concurrent.{ExecutionContext, Future}

/** Reconciles the dynamic parameters of the dedicated synchronizer this operator serves. */
class ReconcileDedicatedSynchronizerParametersTrigger(
    override protected val context: TriggerContext,
    synchronizerId: SynchronizerId,
    synchronizerNodeService: SynchronizerNodeService[SyncOperatorSynchronizerNode],
    trafficControlConfig: SyncOperatorTrafficControlConfig,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task] {

  private def sequencerAdminConnection()(implicit
      tc: TraceContext
  ): Future[SequencerAdminConnection] =
    synchronizerNodeService.sequencerAdminConnection()

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] =
    for {
      connection <- sequencerAdminConnection()
      reconciled <- isReconciled(connection)
    } yield if (reconciled) Seq.empty else Seq(Task(synchronizerId))

  override protected def completeTask(task: Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    sequencerAdminConnection()
      .flatMap(_.ensureDomainParameters(task.synchronizerId, withTrafficControl))
      .map(_ => TaskSuccess(s"Set the traffic control parameters on ${task.synchronizerId}"))

  override protected def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    sequencerAdminConnection()
      .flatMap(isReconciled(_))

  private def isReconciled(
      connection: SequencerAdminConnection
  )(implicit tc: TraceContext): Future[Boolean] =
    connection
      .getSynchronizerParametersState(synchronizerId)
      .map(state => state.mapping.parameters == withTrafficControl(state.mapping.parameters))

  /** Applies the target parameters, leaving the rest as the synchronizer has them */
  private def withTrafficControl(
      parameters: DynamicSynchronizerParameters
  ): DynamicSynchronizerParameters =
    parameters.trafficControl.fold(parameters)(current =>
      parameters.tryUpdate(trafficControlParameters =
        Some(
          current.copy(
            // Zero base amount so that all traffic is paid for.
            maxBaseTrafficAmount = NonNegativeLong.zero,
            readVsWriteScalingFactor = trafficControlConfig.readVsWriteScalingFactor,
            setBalanceRequestSubmissionWindowSize = PositiveFiniteDuration.fromConfig(
              trafficControlConfig.setBalanceRequestSubmissionWindowSize
            ),
            baseEventCost = trafficControlConfig.baseEventCost,
            freeConfirmationResponses = true,
          )
        )
      )
    )
}

object ReconcileDedicatedSynchronizerParametersTrigger {

  final case class Task(synchronizerId: SynchronizerId) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("synchronizerId", _.synchronizerId)
      )
  }
}

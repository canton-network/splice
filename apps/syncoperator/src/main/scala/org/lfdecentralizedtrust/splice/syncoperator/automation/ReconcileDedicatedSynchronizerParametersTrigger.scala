// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.automation

import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.DynamicSynchronizerParameters
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
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.syncoperator.automation.ReconcileDedicatedSynchronizerParametersTrigger.Task
import org.lfdecentralizedtrust.splice.syncoperator.store.SyncOperatorStore
import org.lfdecentralizedtrust.splice.util.AmuletConfigSchedule
import org.lfdecentralizedtrust.splice.environment.SynchronizerNodeService
import org.lfdecentralizedtrust.splice.syncoperator.SyncOperatorSynchronizerNode

import scala.concurrent.{ExecutionContext, Future}

/** Reconciles the dynamic parameters of the dedicated synchronizer this operator serves. */
class ReconcileDedicatedSynchronizerParametersTrigger(
    override protected val context: TriggerContext,
    store: SyncOperatorStore,
    synchronizerNodeService: SynchronizerNodeService[SyncOperatorSynchronizerNode],
    scanConnection: ScanConnection,
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
      readVsWriteScalingFactor <- globalReadVsWriteScalingFactor()
      reconciled <- isReconciled(connection, readVsWriteScalingFactor)
    } yield if (reconciled) Seq.empty else Seq(Task(synchronizerId, readVsWriteScalingFactor))

  override protected def completeTask(task: Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    synchronizerNodeService
      .sequencerAdminConnection()
      .flatMap(connection =>
        connection.ensureDomainParameters(
          task.synchronizerId,
          withTrafficControl(_, task.readVsWriteScalingFactor),
        )
      )
      .map(_ =>
        TaskSuccess(
          s"Set the traffic control parameters on ${task.synchronizerId}, " +
            s"read vs write scaling factor ${task.readVsWriteScalingFactor.value}"
        )
      )

  override protected def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    synchronizerNodeService
      .sequencerAdminConnection()
      .flatMap(isReconciled(_, task.readVsWriteScalingFactor))

  private def isReconciled(
      connection: SequencerAdminConnection,
      readVsWriteScalingFactor: PositiveInt,
  )(implicit tc: TraceContext): Future[Boolean] =
    connection
      .getSynchronizerParametersState(synchronizerId)
      .map(state =>
        state.mapping.parameters ==
          withTrafficControl(state.mapping.parameters, readVsWriteScalingFactor)
      )

  /** The global synchronizer's read cost, from the amulet config served by Scan. */
  private def globalReadVsWriteScalingFactor()(implicit tc: TraceContext): Future[PositiveInt] =
    scanConnection.getAmuletRulesWithState().map { amuletRules =>
      val fees = AmuletConfigSchedule(amuletRules)
        .getConfigAsOf(context.clock.now)
        .decentralizedSynchronizer
        .fees
      PositiveInt.tryCreate(fees.readVsWriteScalingFactor.toInt)
    }

  /** Applies the target parameters, leaving the rest as the synchronizer has them */
  private def withTrafficControl(
      parameters: DynamicSynchronizerParameters,
      readVsWriteScalingFactor: PositiveInt,
  ): DynamicSynchronizerParameters =
    parameters.trafficControl.fold(parameters)(current =>
      parameters.tryUpdate(trafficControlParameters =
        Some(
          current.copy(
            // Zero base amount so that all traffic is paid for.
            maxBaseTrafficAmount = NonNegativeLong.zero,
            readVsWriteScalingFactor = readVsWriteScalingFactor,
            freeConfirmationResponses = true,
          )
        )
      )
    )
}

object ReconcileDedicatedSynchronizerParametersTrigger {

  final case class Task(synchronizerId: SynchronizerId, readVsWriteScalingFactor: PositiveInt)
      extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("synchronizerId", _.synchronizerId),
        param("readVsWriteScalingFactor", _.readVsWriteScalingFactor.value),
      )
  }
}

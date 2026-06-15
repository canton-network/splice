// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import cats.syntax.either.*
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.admin.api.client.data.SequencingParameters as ConsoleSequencingParameters
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.framework.data.topology.SequencingParameters
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.topology.transaction.SequencingParametersState
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReconcileSequencingParametersTrigger.Task
import org.lfdecentralizedtrust.splice.sv.config.BftSequencingParameters
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class ReconcileSequencingParametersTrigger(
    override protected val context: TriggerContext,
    val participantAdminConnection: ParticipantAdminConnection,
    configParametersO: Option[BftSequencingParameters],
    alias: SynchronizerAlias,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task]
    with SyncConnectionStalenessCheck {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] =
    for {
      synchronizerId <- participantAdminConnection.getPhysicalSynchronizerId(alias)
      parametersO = configParametersO.map(_.toInternal(synchronizerId.protocolVersion))
      stateO <- participantAdminConnection.lookupSequencingParametersState(synchronizerId.logical)
    } yield {
      if (stateO.map(_.mapping) != parametersO.map(_.toByteString)) {
        Seq(Task(synchronizerId, parametersO))
      } else {
        Seq.empty
      }
    }

  override protected def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    (task.parameters match {
      case Some(parameters) =>
        participantAdminConnection.ensureSequencingParametersState(
          task.synchronizerId.logical,
          SequencingParametersState(
            task.synchronizerId.logical,
            ConsoleSequencingParameters(Some(parameters.toByteString)).toInternal.valueOr(err =>
              throw new IllegalStateException(s"Failed to convert sequencing parameters: $err")
            ),
          ),
        )
      case None =>
        participantAdminConnection.ensureTopologyMappingRemoved(
          "remove sequencing parameters",
          task.synchronizerId.logical,
          participantAdminConnection.lookupSequencingParametersState(task.synchronizerId.logical),
        )
    }).map(_ => TaskSuccess("Updated sequencing parameters"))

  override def isStaleTask(
      task: Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    isNotConnectedToSync()
  }
}

object ReconcileSequencingParametersTrigger {
  case class Task(
      synchronizerId: PhysicalSynchronizerId,
      parameters: Option[SequencingParameters],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("synchronizerId", _.synchronizerId),
        param("parameters", _.parameters),
      )
  }
}

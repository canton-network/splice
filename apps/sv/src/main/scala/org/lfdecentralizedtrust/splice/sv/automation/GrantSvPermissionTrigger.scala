// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.topology.transaction.ParticipantPermission.Submission
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.svonboarding.SvOnboardingConfirmed
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.{Contract, SwitchOverTimes}

import scala.concurrent.{ExecutionContext, Future}

class GrantSvPermissionTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[GrantSvPermissionTrigger.Task] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[GrantSvPermissionTrigger.Task]] = {
    import cats.implicits.*
    import com.digitalasset.canton.util.FutureInstances.*
    for {
      dsoRules <- store.getDsoRules()
      tasks <-
        if (!SwitchOverTimes.permissionedSynchronizerScheduled(dsoRules.payload)) {
          Future.successful(Seq.empty)
        } else {
          for {
            confirmations <- store.listSvOnboardingConfirmed()
            unpermissionedConfirmations <- confirmations.toList.parFilterA { confirmation =>
              participantAdminConnection
                .listParticipantSynchronizerPermission(
                  SynchronizerId.tryFromString(
                    dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
                  ),
                  confirmation.payload.svParticipantId,
                )
                .map(permissions => !permissions.exists(_.mapping.permission == Submission))
            }
          } yield unpermissionedConfirmations.map(GrantSvPermissionTrigger.Task(_))
        }
    } yield tasks
  }

  override protected def completeTask(
      task: GrantSvPermissionTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val payload = task.svOnboardingConfirmed.payload

    ParticipantId
      .fromProtoPrimitive(payload.svParticipantId, "svParticipantId")
      .fold(
        err => {
          Future.successful(
            TaskSuccess(s"Skipping SvOnboardingConfirmed with invalid participantId: $err")
          )
        },
        participantId => {
          for {
            dsoRules <- store.getDsoRules()
            outcome <- {
              val synchronizerId = SynchronizerId.tryFromString(
                dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
              )
              participantAdminConnection
                .ensureParticipantSynchronizerPermission(
                  synchronizerId = synchronizerId,
                  participantId = participantId,
                  permission = Submission,
                  retryFor = RetryFor.Automation,
                )
                .map { _ =>
                  TaskSuccess(
                    s"Granted Submission permission for SV participant $participantId"
                  )
                }
            }

          } yield outcome
        },
      )
  }

  override protected def isStaleTask(
      task: GrantSvPermissionTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    for {
      isArchived <- store.multiDomainAcsStore
        .lookupContractById(SvOnboardingConfirmed.COMPANION)(task.svOnboardingConfirmed.contractId)
        .map(_.isEmpty)

      isAlreadyPermissioned <-
        if (isArchived) Future.successful(false)
        else {
          for {
            dsoRules <- store.getDsoRules()
            synchronizerId = SynchronizerId.tryFromString(
              dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
            )
            permissions <- participantAdminConnection
              .listParticipantSynchronizerPermission(
                synchronizerId,
                task.svOnboardingConfirmed.payload.svParticipantId,
              )
          } yield permissions.exists(_.mapping.permission == Submission)
        }
    } yield isArchived || isAlreadyPermissioned
  }
}

object GrantSvPermissionTrigger {
  final case class Task(
      svOnboardingConfirmed: Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("contractId", _.svOnboardingConfirmed.contractId.contractId.unquoted),
      param("svParticipantId", _.svOnboardingConfirmed.payload.svParticipantId.unquoted),
    )
  }
}

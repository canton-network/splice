// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
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
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.SwitchOverTimes

import scala.concurrent.{ExecutionContext, Future}

class GrantExistingValidatorPermissionTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[GrantExistingValidatorPermissionTrigger.Task] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[GrantExistingValidatorPermissionTrigger.Task]] = {
    import cats.implicits.*
    import com.digitalasset.canton.util.FutureInstances.*

    for {
      dsoRules <- store.getDsoRules()
      tasks <- SwitchOverTimes.getPermissionedSynchronizerSwitchOverTime(dsoRules.payload) match {
        case None =>
          Future.successful(Seq.empty)
        case Some(switchOverInstant) =>
          val synchronizerId = SynchronizerId.tryFromString(
            dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
          )
          for {
            allPtp <- participantAdminConnection.listPartyToParticipant(
              store = Some(TopologyStoreId.Synchronizer(synchronizerId))
            )
            allParticipants = allPtp.flatMap(_.mapping.participantIds).distinct

            onboardedParticipants <- allParticipants.toList.parFilterA { participantId =>
              participantAdminConnection
                .listSynchronizerTrustCertificate(synchronizerId, participantId)
                .map { certs =>
                  certs.exists(_.base.validFrom.isBefore(switchOverInstant))
                }
            }

            allowedParticipants <- onboardedParticipants.parFilterA { participantId =>
              store.listValidatorUnpermissions(participantId.toProtoPrimitive).map(_.isEmpty)
            }

            unpermissionedParticipants <- allowedParticipants.parFilterA { participantId =>
              participantAdminConnection
                .listParticipantSynchronizerPermission(
                  synchronizerId,
                  participantId.filterString,
                )
                .map(permissions => !permissions.exists(_.mapping.permission == Submission))
            }
          } yield unpermissionedParticipants.map(p =>
            GrantExistingValidatorPermissionTrigger.Task(p.toProtoPrimitive)
          )
      }
    } yield tasks
  }

  override protected def completeTask(
      task: GrantExistingValidatorPermissionTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    ParticipantId
      .fromProtoPrimitive(task.participantId, "participantId")
      .fold(
        err => {
          Future.successful(
            TaskSuccess(s"Skipping task with invalid participantId: $err")
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
                    s"Granted Submission permission for existing participant $participantId"
                  )
                }
            }

          } yield outcome
        },
      )
  }

  override protected def isStaleTask(
      task: GrantExistingValidatorPermissionTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    ParticipantId.fromProtoPrimitive(task.participantId, "participantId") match {
      case Left(_) => Future.successful(true)
      case Right(participantId) =>
        for {
          dsoRules <- store.getDsoRules()
          synchronizerId = SynchronizerId.tryFromString(
            dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
          )
          permissions <- participantAdminConnection
            .listParticipantSynchronizerPermission(
              synchronizerId,
              participantId.filterString,
            )
        } yield permissions.exists(_.mapping.permission == Submission)
    }
  }
}

object GrantExistingValidatorPermissionTrigger {
  final case class Task(
      participantId: String
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("participantId", _.participantId.unquoted)
    )
  }
}

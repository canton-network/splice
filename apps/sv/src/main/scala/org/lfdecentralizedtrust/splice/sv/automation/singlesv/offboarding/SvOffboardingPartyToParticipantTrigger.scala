// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv.offboarding

import cats.implicits.showInterpolator
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologySnapshot
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SyncConnectionStalenessCheck
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala

/** Offboard a participant from the hosting of the DSO party.
  * - Runs when one of offboardedSvs still exist on the PartyToParticipant mapping
  * - Skips participants that are still the participant of an active SV in the DsoRules
  */
class SvOffboardingPartyToParticipantProposalTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    val participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[ParticipantId]
    with SyncConnectionStalenessCheck {

  import SvOffboardingPartyToParticipantProposalTrigger.*

  private val dsoParty: PartyId = dsoStore.key.dsoParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ParticipantId]] = {
    for {
      dsoRules <- dsoStore.getDsoRules()
      currentHostingParticipantIds <- participantAdminConnection
        .getPartyToParticipant(
          dsoRules.domain,
          dsoParty,
          topologySnapshot = TopologySnapshot.Sequenced,
        )
        .map(_.mapping.participantIds)
    } yield {
      val result = participantsToOffboard(
        offboardedParticipants = dsoRules.contract.payload.offboardedSvs
          .values()
          .asScala
          .map(_.participantId)
          .toSeq,
        activeSvParticipants = dsoRules.contract.payload.svs
          .values()
          .asScala
          .map(_.participantId)
          .toSeq,
        currentlyHostingParticipants = currentHostingParticipantIds,
      )
      result.stillUsedByActiveSv.foreach { participantId =>
        logger.warn(
          s"Participant $participantId is listed for an offboarded SV but is still the participant of an active SV in the DsoRules, not removing it from the hosting of the DSO party"
        )
      }
      result.toOffboard
    }
  }

  override protected def completeTask(task: ParticipantId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    logger.info(
      s"Removing participant with participantId $task from the hosting of the DSO party"
    )
    for {
      dsoRules <- dsoStore.getDsoRules()
      _ <- participantAdminConnection.ensurePartyToParticipantRemovalProposal(
        dsoRules.domain,
        dsoParty,
        task,
      )
    } yield {
      TaskSuccess(show"Removed $task from the participant hosting the DSO party $dsoParty")
    }
  }

  // proposing is safe and it checks when running the task so no need to duplicate the same check here
  override protected def isStaleTask(task: ParticipantId)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    for {
      dsoRules <- dsoStore.getDsoRules()
      currentHostingParticipants <- participantAdminConnection
        .getPartyToParticipant(
          dsoRules.domain,
          dsoParty,
          topologySnapshot = TopologySnapshot.Sequenced,
        )
      notConnected <- isNotConnectedToSync()
    } yield {
      !currentHostingParticipants.mapping.participantIds.contains(task) || notConnected
    }
  }
}

object SvOffboardingPartyToParticipantProposalTrigger {

  final case class OffboardingResult(
      toOffboard: Seq[ParticipantId],
      stillUsedByActiveSv: Seq[ParticipantId],
  )

  def participantsToOffboard(
      offboardedParticipants: Seq[String],
      activeSvParticipants: Seq[String],
      currentlyHostingParticipants: Seq[ParticipantId],
  ): OffboardingResult = {
    val offboarded = offboardedParticipants.map(ParticipantId.tryFromProtoPrimitive).toSet
    val active = activeSvParticipants.map(ParticipantId.tryFromProtoPrimitive).toSet
    val (stillUsedByActiveSv, toOffboard) =
      currentlyHostingParticipants.filter(offboarded.contains).partition(active.contains)
    OffboardingResult(toOffboard, stillUsedByActiveSv)
  }
}

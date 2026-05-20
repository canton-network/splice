// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicenserequest.ValidatorLicenseRequest
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.util.AssignedContract

import scala.concurrent.{ExecutionContext, Future}

class ValidatorLicenseRequestTrigger(
    override protected val context: TriggerContext,
    svStore: SvSvStore,
    dsoStore: SvDsoStore,
    connection: SpliceLedgerConnection,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      ValidatorLicenseRequest.ContractId,
      ValidatorLicenseRequest,
    ](
      svStore,
      ValidatorLicenseRequest.COMPANION,
    ) {

  override protected def completeTask(
      task: AssignedContract[
        ValidatorLicenseRequest.ContractId,
        ValidatorLicenseRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val payload = task.payload
    val svParty = svStore.key.svParty

    logger.info(
      s"Running ValidatorLicenseRequestTrigger for ${payload.validator} ValidatorLicenseRequest"
    )

    if (payload.sponsor != svParty.toProtoPrimitive) {
      logger.debug(
        s"Skipping ValidatorLicenseRequest ${task.contractId} as it is sponsored by ${payload.sponsor}, not us ($svParty)"
      )
      Future.successful(TaskSuccess(s"Should be handled by ${payload.sponsor}"))
    } else {

      val validatorParty = PartyId.tryFromProtoPrimitive(payload.validator)
      val synchronizerId = task.domain

      for {
        existingLicense <- dsoStore.lookupValidatorLicenseWithOffset(validatorParty)

        outcome <- existingLicense.value match {
          case Some(_) =>
            logger.info(
              s"ValidatorLicense already exists for $validatorParty. Rejecting redundant ValidatorLicenseRequest."
            )
            connection
              .submit(
                actAs = Seq(svParty),
                readAs = Seq.empty,
                update = Seq(
                  task.contractId.exerciseValidatorLicenseRequest_Reject(
                    "ValidatorLicense already exists"
                  )
                ),
              )
              .withSynchronizerId(synchronizerId)
              .noDedup
              .yieldUnit()
              .map(_ =>
                TaskSuccess(s"Rejected duplicate ValidatorLicenseRequest for ${payload.validator}")
              )

          case None =>
            for {
              partyToParticipantResults <- participantAdminConnection.listPartyToParticipant(
                store = Some(TopologyStoreId.Synchronizer(synchronizerId)),
                filterParty = validatorParty.filterString,
              )

              participantIds = partyToParticipantResults.flatMap(_.mapping.participantIds).distinct

              _ <-
                if (participantIds.isEmpty) {
                  Future.failed(
                    Status.NOT_FOUND
                      .withDescription(
                        s"No participant IDs found registered to party $validatorParty"
                      )
                      .asRuntimeException()
                  )
                } else Future.unit

              permissionResults <- Future
                .sequence(
                  participantIds.map(pid =>
                    participantAdminConnection.listParticipantSynchronizerPermission(
                      synchronizerId = synchronizerId,
                      filterUid = pid.filterString,
                    )
                  )
                )
                .map(_.flatten)

              _ <-
                if (permissionResults.nonEmpty) {
                  Future.unit
                } else {
                  Future.failed(
                    Status.NOT_FOUND
                      .withDescription(
                        s"ParticipantSynchronizerPermission not yet found for any participant of $validatorParty. Checked participants: ${participantIds
                            .mkString(", ")}"
                      )
                      .asRuntimeException()
                  )
                }

              dsoRules <- dsoStore.getDsoRules()

              cmd1 = task.contractId.exerciseValidatorLicenseRequest_Accept()
              cmd2 = dsoRules.contractId.exerciseDsoRules_OnboardValidator(
                svParty.toProtoPrimitive,
                payload.validator,
                java.util.Optional.of(payload.version),
                java.util.Optional.of(payload.contactPoint),
              )

              _ <- connection
                .submit(
                  actAs = Seq(svParty),
                  readAs = Seq(dsoStore.key.dsoParty),
                  update = Seq(cmd1, cmd2),
                )
                .withSynchronizerId(synchronizerId)
                .noDedup
                .yieldUnit()

            } yield TaskSuccess(
              s"Accepted ValidatorLicenseRequest for validator ${payload.validator} and created ValidatorLicense"
            )
        }
      } yield outcome
    }

  }
}

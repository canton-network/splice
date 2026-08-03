// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_GrantValidatorLicense
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules,
  DsoRules_GrantValidatorLicense,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicenseRequest
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupOffset
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract

import scala.concurrent.{ExecutionContext, Future}

class ValidatorLicenseRequestTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      ValidatorLicenseRequest.ContractId,
      ValidatorLicenseRequest,
    ](
      dsoStore,
      ValidatorLicenseRequest.COMPANION,
    ) {

  private val svParty = dsoStore.key.svParty
  private val dsoParty = dsoStore.key.dsoParty

  override def completeTask(
      request: AssignedContract[
        ValidatorLicenseRequest.ContractId,
        ValidatorLicenseRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val reqCid = request.contractId
    val validatorParty = PartyId.tryFromProtoPrimitive(request.payload.validator)

    for {
      dsoRules <- dsoStore.getDsoRules()
      synchronizerId = SynchronizerId.tryFromString(
        dsoRules.payload.config.decentralizedSynchronizer.activeSynchronizerId
      )

      partyToParticipant <- participantAdminConnection.listPartyToParticipant(
        store = Some(TopologyStoreId.Synchronizer(synchronizerId)),
        filterParty = validatorParty.filterString,
      )

      outcome <- partyToParticipant.headOption.flatMap(_.mapping.participantIds.headOption) match {
        case None =>
          Future.successful(
            TaskSuccess(
              s"Skipping as participant ID for $validatorParty is not yet known to local topology"
            )
          )
        case Some(participantId) =>
          participantAdminConnection
            .listParticipantSynchronizerPermission(synchronizerId, participantId.filterString)
            .flatMap { permissions =>
              permissions.headOption.map(_.mapping) match {
                case None =>
                  Future.successful(
                    TaskSuccess(
                      s"Skipping as participant $participantId does not have ParticipantSynchronizerPermission"
                    )
                  )
                case Some(permission) =>
                  val isLoginAfterPassed =
                    permission.loginAfter.forall(loginAfter => context.clock.now >= loginAfter)

                  if (isLoginAfterPassed) {
                    confirm(reqCid, validatorParty, dsoRules)
                  } else {
                    Future.successful(
                      TaskSuccess(
                        s"Skipping as participant $participantId has permission but loginAfter barrier is not yet passed"
                      )
                    )
                  }
              }
            }
      }
    } yield outcome
  }

  private def confirm(
      reqCid: ValidatorLicenseRequest.ContractId,
      validatorParty: PartyId,
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {

    val action = new ARC_DsoRules(
      new SRARC_GrantValidatorLicense(
        new DsoRules_GrantValidatorLicense(reqCid)
      )
    )

    for {
      queryResult <- dsoStore.lookupConfirmationByActionWithOffset(svParty, action)
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ConfirmAction(
          svParty.toProtoPrimitive,
          action,
        )
      )
      outcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          Future.successful(
            TaskSuccess(
              s"Skipping as confirmation from $svParty is already created for granting validator license to $validatorParty"
            )
          )
        case QueryResult(offset, None) =>
          connection
            .submit(
              actAs = Seq(svParty),
              readAs = Seq(dsoParty),
              update = cmd,
            )
            .withDedup(
              commandId = SpliceLedgerConnection.CommandId(
                "org.lfdecentralizedtrust.splice.sv.confirmGrantValidatorLicense",
                Seq(svParty, dsoParty),
                validatorParty.toProtoPrimitive,
              ),
              deduplicationConfig = DedupOffset(offset),
            )
            .yieldResult()
            .map { _ =>
              TaskSuccess(
                s"Created confirmation for granting validator license to $validatorParty"
              )
            }
      }
    } yield outcome
  }
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskNoop,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  Confirmation,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.{
  CRARC_MiningRound_Archive,
  CRARC_MiningRound_StartIssuing,
  CRARC_StartProcessingRewardsV2,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Reports mismatches in automated confirmations
  *
  * Under normal operation each SV should compute identical confirmation for [[ARC_AmuletRules]] actions.
  * Discrepencies in confirmations is an indicator of a problem and should be notified to SV operators.
  */
class ConfirmationMismatchReportTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      Confirmation.ContractId,
      Confirmation,
    ](
      store,
      Confirmation.COMPANION,
    ) {

  // Extracts the contractId from the set of actions being checked by this trigger.
  // Confirmation types not handled by this API are not checked by the trigger.
  private def getTargetContractId(action: ActionRequiringConfirmation): Option[String] =
    action match {
      case arc: ARC_AmuletRules =>
        arc.amuletRulesAction match {
          case a: CRARC_StartProcessingRewardsV2 =>
            Some(a.amuletRules_StartProcessingRewardsV2Value.calculateRewardsCid.contractId)
          case a: CRARC_MiningRound_StartIssuing =>
            Some(a.amuletRules_MiningRound_StartIssuingValue.miningRoundCid.contractId)
          case a: CRARC_MiningRound_Archive =>
            Some(a.amuletRules_MiningRound_ArchiveValue.closedRoundCid.contractId)
          case _ => None
        }
      case _ => None
    }

  override def completeTask(
      confirmationContract: AssignedContract[
        Confirmation.ContractId,
        Confirmation,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val confirmation = confirmationContract.payload
    val action = confirmation.action
    getTargetContractId(action) match {
      case None =>
        Future.successful(TaskNoop)
      case Some(targetCid) =>
        store.listAllConfirmations().map { allConfirmations =>
          val disagreeing = allConfirmations.filter { other =>
            other.contractId != confirmationContract.contractId &&
            getTargetContractId(other.payload.action).contains(targetCid) &&
            other.payload.action != action
          }
          if (disagreeing.isEmpty)
            TaskSuccess(
              s"Confirmation ${confirmationContract.contractId} by ${confirmation.confirmer} " +
                s"agrees with all other currently existing confirmations."
            )
          else {
            val others = disagreeing
              .map(other => s"${other.payload.confirmer}: ConfirmationId ${other.contractId}")
              .mkString(System.lineSeparator())
            val message =
              s"Confirmation ${confirmationContract.contractId} by ${confirmation.confirmer} " +
                s"for the action on contract $targetCid " +
                s"has a mismatch with confirmations submitted by the following SV(s) " +
                s"$others"
            logger.warn(message)
            TaskSuccess(
              s"Found ${disagreeing.size} confirmation(s) disagreeing with confirmation " +
                s"${confirmationContract.contractId} by ${confirmation.confirmer}"
            )
          }
        }
    }
  }
}

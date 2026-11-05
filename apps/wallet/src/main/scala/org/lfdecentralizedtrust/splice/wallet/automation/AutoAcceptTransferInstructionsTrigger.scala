// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulettransferinstruction.AmuletTransferInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.environment.{CommandPriority, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Codec, DisclosedContracts}
import org.lfdecentralizedtrust.splice.wallet.config.AutoAcceptTransfersConfig
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.util.{TopupUtil, ValidatorTopupConfig}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class AutoAcceptTransferInstructionsTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: SpliceLedgerConnection,
    autoAcceptTransfers: AutoAcceptTransfersConfig,
    scanConnection: BftScanConnection,
    validatorTopupConfigO: Option[ValidatorTopupConfig],
    clock: Clock,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    mat: Materializer,
) extends OnAssignedContractTrigger.Template[
      AmuletTransferInstruction.ContractId,
      AmuletTransferInstruction,
    ](store, AmuletTransferInstruction.COMPANION) {

  override protected def extraMetricLabels = Seq(
    "party" -> store.key.endUserParty.toString
  )

  override protected def completeTask(
      instruction: AutoAcceptTransferInstructionsTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val transfer = instruction.contract.payload.transfer
    if (transfer.receiver != store.key.endUserParty.toProtoPrimitive) {
      Future.successful(TaskSuccess("Transfer instruction is not addressed to us"))
    } else if (
      !autoAcceptTransfers.fromParties.contains(Codec.tryDecode(Codec.Party)(transfer.sender))
    ) {
      Future.successful(
        TaskSuccess(
          s"Not configured to auto-accept transfer instructions from sender: ${transfer.sender}"
        )
      )
    } else if (!clock.now.toInstant.isBefore(transfer.executeBefore)) {
      Future.successful(
        TaskSuccess(s"Transfer instruction expired at ${transfer.executeBefore}, not accepting it")
      )
    } else {
      val instructionCid = new transferinstructionv1.TransferInstruction.ContractId(
        instruction.contractId.contractId
      )
      for {
        install <- store.getInstall()
        choiceContext <- scanConnection.getTransferInstructionAcceptContextV2(instructionCid)
        cmd = install.exercise(
          _.exerciseWalletAppInstall_TransferInstruction_Accept(
            instructionCid,
            new transferinstructionv1.TransferInstruction_Accept(choiceContext.toExtraArgs()),
          )
        )
        // validatorTopupConfigO exist iff we are the validator party
        commandPriority <- validatorTopupConfigO match {
          case None => Future.successful(CommandPriority.Low)
          case Some(validatorTopupConfig) =>
            TopupUtil.highPriorityIncreaseBalanceForTopup(
              scanConnection,
              store,
              validatorTopupConfig,
              clock,
            )
        }
        res <- connection
          .submit(
            Seq(store.key.validatorParty),
            Seq(store.key.endUserParty),
            cmd,
            priority = commandPriority,
          )
          .withDisclosedContracts(DisclosedContracts.fromProto(choiceContext.disclosedContracts))
          .noDedup
          .yieldResult()
      } yield TaskSuccess(s"Accepted transfer instruction: $res")
    }
  }
}

object AutoAcceptTransferInstructionsTrigger {
  final type Task = AssignedContract[
    AmuletTransferInstruction.ContractId,
    AmuletTransferInstruction,
  ]
}

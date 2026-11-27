// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.UnallocatedUnclaimedActivityRecord
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class AllocateUnallocatedUnclaimedActivityRecordTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      UnallocatedUnclaimedActivityRecord.ContractId,
      UnallocatedUnclaimedActivityRecord,
    ](
      svTaskContext.dsoStore,
      UnallocatedUnclaimedActivityRecord.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[
      UnallocatedUnclaimedActivityRecord.ContractId,
      UnallocatedUnclaimedActivityRecord,
    ]] {
  private type UnallocatedUnclaimedActivityRecordContract = AssignedContract[
    UnallocatedUnclaimedActivityRecord.ContractId,
    UnallocatedUnclaimedActivityRecord,
  ]

  override def completeTaskAsDsoDelegate(
      unallocatedUnclaimedActivityRecord: UnallocatedUnclaimedActivityRecordContract,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val store = svTaskContext.dsoStore
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      requiredAmount = unallocatedUnclaimedActivityRecord.payload.amount
      unclaimedRewardsToBurnCids <- UnclaimedRewardsUtil
        .collectSufficientUnclaimedRewards(store, requiredAmount)
        .flatMap {
          case Right(rewards) => Future.successful(rewards.map(_.contractId))
          case Left(UnclaimedRewardsUtil.Shortfall(missing)) =>
            val errorMsg = s"Insufficient rewards: $missing"
            logger.warn(errorMsg)
            Future.failed(Status.INTERNAL.withDescription(errorMsg).asRuntimeException())
        }
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_AllocateUnallocatedUnclaimedActivityRecord(
          unallocatedUnclaimedActivityRecord.contract.contractId,
          amuletRules.contractId,
          unclaimedRewardsToBurnCids.asJava,
          controller,
        )
      )
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Medium)
        .submit(
          Seq(store.key.svParty),
          Seq(store.key.dsoParty),
          cmd,
        )
        .noDedup
        .yieldUnit()
    } yield TaskSuccess(
      s"allocated unallocated unclaimed activity record"
    )
  }
}

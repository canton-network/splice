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
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_MergeMemberTrafficContracts
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract}
import com.digitalasset.canton.topology.{Member, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes.StoreMemberId

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class MergeMemberTrafficContractsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[MemberTraffic.ContractId, MemberTraffic](
      svTaskContext.dsoStore,
      MemberTraffic.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[MemberTraffic.ContractId, MemberTraffic]] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
      memberTraffic: AssignedContract[MemberTraffic.ContractId, MemberTraffic],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val payload = memberTraffic.payload
    SynchronizerId.fromString(payload.synchronizerId) match {
      case Left(err) =>
        // Skip contracts with invalid synchronizer ids, e.g., a registered synchronizer's malformed id
        Future.successful(TaskSuccess(s"Skipping MemberTraffic with invalid synchronizerId: $err"))
      case Right(synchronizerId) =>
        Member.fromProtoPrimitive_(payload.memberId) match {
          case Right(memberId) =>
            mergeIfAboveThreshold(
              s"member $memberId on domain $synchronizerId",
              limit =>
                store.listMemberTrafficContracts(StoreMemberId(memberId), synchronizerId, limit),
              controller,
            )
          case Left(_) if SvDsoStore.isPaymasterTraffic(payload) =>
            mergeIfAboveThreshold(
              s"the paymaster purchases for synchronizer $synchronizerId",
              limit => store.listPaymasterTrafficContracts(synchronizerId, limit),
              controller,
            )
          case Left(err) =>
            // Skip contracts with invalid member ids
            Future.successful(TaskSuccess(s"Skipping MemberTraffic with invalid memberId: $err"))
        }
    }
  }

  /** @param description the member or paymaster, for the log */
  private def mergeIfAboveThreshold(
      description: String,
      listContracts: PageLimit => Future[Seq[Contract[MemberTraffic.ContractId, MemberTraffic]]],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      dsoRules <- store.getDsoRules()
      threshold = dsoRules.payload.config.numMemberTrafficContractsThreshold
      memberTraffics <- listContracts(PageLimit.tryCreate(2 * threshold.toInt))
      outcome <-
        if (memberTraffics.length > threshold)
          mergeMemberTrafficContracts(description, memberTraffics, controller)
        else
          Future.successful(
            TaskSuccess(
              s"More than ${threshold} member traffic contracts are required for $description " +
                s"in order to merge them. Currently, there are only ${memberTraffics.length}."
            )
          )
    } yield outcome

  private def mergeMemberTrafficContracts(
      description: String,
      memberTraffics: Seq[Contract[MemberTraffic.ContractId, MemberTraffic]],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      arg = new DsoRules_MergeMemberTrafficContracts(
        amuletRules.contractId,
        memberTraffics.map(_.contractId).asJava,
        Optional.of(controller),
      )
      cmd = dsoRules.exercise(_.exerciseDsoRules_MergeMemberTrafficContracts(arg))
      outcome <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(
      s"Merged ${memberTraffics.length} member traffic contracts for $description " +
        s"into contract ${outcome.exerciseResult.memberTraffic.contractId}"
    )
  }

}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsEntry_Expire
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.util.ContractStakeholders

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import ExpiredAnsEntryTrigger.{Task, getStakeholders}
import org.lfdecentralizedtrust.splice.store.IgnoredPartiesStore

class ExpiredAnsEntryTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    override protected val svConfig: SvAppBackendConfig,
    override protected val ignoredPartiesStore: IgnoredPartiesStore,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      splice.ans.AnsEntry.ContractId,
      splice.ans.AnsEntry,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listExpiredAnsEntries(Some(ignoredPartiesStore)),
      splice.ans.AnsEntry.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[AssignedContract[
      splice.ans.AnsEntry.ContractId,
      splice.ans.AnsEntry,
    ]]]
    with IgnoredUnavailablePartiesGuard {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    completeWithVettedAmuletVersion(
      getStakeholders(task.work.payload).toSet,
      Seq(task.work.contractId.contractId),
    )(completeExpiryTaskAsDsoDelegate(task, controller))

  private def completeExpiryTaskAsDsoDelegate(
      task: Task,
      controller: String,
  )(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    for {
      dsoRules <- store.getDsoRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ExpireAnsEntry(
          task.work.contractId,
          new AnsEntry_Expire(store.key.dsoParty.toProtoPrimitive),
          Optional.of(controller),
        )
      )
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldUnit()
    } yield TaskSuccess("archived expired ANS entry")
}

object ExpiredAnsEntryTrigger extends ContractStakeholders[splice.ans.AnsEntry] {
  type Task = ScheduledTaskTrigger.ReadyTask[
    AssignedContract[
      splice.ans.AnsEntry.ContractId,
      splice.ans.AnsEntry,
    ]
  ]

  override def informees(payload: splice.ans.AnsEntry): Seq[String] = Seq(payload.user)

  override def dso(payload: splice.ans.AnsEntry): String = payload.dso
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import cats.data.OptionT
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans as ansCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions as subsCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions.SubscriptionIdleState_ExpireSubscription
import org.lfdecentralizedtrust.splice.store.{IgnoredPartiesStore, PageLimit}
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.util.ContractStakeholders
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import ExpiredAnsSubscriptionTrigger.{Task, getStakeholders}

class ExpiredAnsSubscriptionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    override protected val svConfig: SvAppBackendConfig,
    override protected val ignoredPartiesStore: IgnoredPartiesStore,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[SvDsoStore.IdleAnsSubscription]
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[SvDsoStore.IdleAnsSubscription]]
    with IgnoredUnavailablePartiesGuard {
  private val store = svTaskContext.dsoStore

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[SvDsoStore.IdleAnsSubscription]] =
    store.listExpiredAnsSubscriptions(now, PageLimit.tryCreate(limit), Some(ignoredPartiesStore))

  override protected def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    completeWithVettedAmuletVersion(
      getStakeholders(task.work.state.payload).toSet,
      Seq(task.work.state.contractId.contractId),
    )(completeExpiryTaskAsDsoDelegate(task, controller))

  private def completeExpiryTaskAsDsoDelegate(
      task: Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    dsoRules <- store.getDsoRules()
    cmd = dsoRules.exercise(
      _.exerciseDsoRules_ExpireSubscription(
        task.work.context.contractId,
        task.work.state.contractId,
        new SubscriptionIdleState_ExpireSubscription(store.key.dsoParty.toProtoPrimitive),
        Optional.of(controller),
      )
    )
    result <- svTaskContext
      .connection(SpliceLedgerConnectionPriority.Low)
      .submit(
        actAs = Seq(store.key.svParty),
        readAs = Seq(store.key.dsoParty),
        cmd,
      )
      .noDedup
      .yieldUnit()
      .map(_ => TaskSuccess(s"archived expired ans subscription"))

  } yield result

  override protected def isStaleTask(
      task: Task
  )(implicit tc: TraceContext): Future[Boolean] =
    (for {
      _ <- OptionT(
        store.multiDomainAcsStore.lookupContractById(
          subsCodegen.SubscriptionIdleState.COMPANION
        )(
          task.work.state.contractId
        )
      )
      _ <- OptionT(
        store.multiDomainAcsStore.lookupContractById(
          ansCodegen.AnsEntryContext.COMPANION
        )(
          task.work.context.contractId
        )
      )
    } yield ()).isEmpty
}

object ExpiredAnsSubscriptionTrigger
    extends ContractStakeholders[subsCodegen.SubscriptionIdleState] {
  type Task = ScheduledTaskTrigger.ReadyTask[SvDsoStore.IdleAnsSubscription]

  override def informees(payload: subsCodegen.SubscriptionIdleState): Seq[String] =
    Seq(
      payload.subscriptionData.sender,
      payload.subscriptionData.receiver,
      payload.subscriptionData.provider,
    )

  override def dso(payload: subsCodegen.SubscriptionIdleState): String =
    payload.subscriptionData.dso
}

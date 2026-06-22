// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskNoop,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_UnhideRewardCouponsV2
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

import UnhideRewardCouponV2Trigger.*

class UnhideRewardCouponV2Trigger(
    svConfig: SvAppBackendConfig,
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task]
    with SvTaskBasedTrigger[Task] {
  private val store = svTaskContext.dsoStore

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    val now = context.clock.now
    for {
      providerParties <- store.listNonObserverRewardCouponsV2ProvidersSample(
        PageLimit.tryCreate(svConfig.delegatelessAutomationUnhideRewardCouponV2SampleSize)
      )
      supported <- Future.traverse(providerParties) { party =>
        svTaskContext.packageVersionSupport
          .supportsTrafficBasedAppRewards(Seq(party), now)
          .map(support => (party, support.supported))
      }
    } yield supported.collect { case (party, true) => Task(party) }
  }

  override protected def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    val now = context.clock.now
    for {
      coupons <- store.listNonObserverRewardCouponsV2ForProvider(
        task.providerParty,
        PageLimit.tryCreate(1),
      )
      support <- svTaskContext.packageVersionSupport
        .supportsTrafficBasedAppRewards(Seq(task.providerParty), now)
    } yield coupons.isEmpty || !support.supported
  }

  override def completeTaskAsDsoDelegate(
      task: Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val batchSize = svConfig.delegatelessAutomationUnhideRewardCouponV2BatchSize
    for {
      coupons <- store.listNonObserverRewardCouponsV2ForProvider(
        task.providerParty,
        PageLimit.tryCreate(batchSize),
      )
      result <-
        if (coupons.isEmpty)
          Future.successful(TaskNoop)
        else {
          val cids = coupons.map(_.contractId).asJava
          val beneficiaries: java.util.List[String] = coupons
            .map(_.payload.provider)
            .distinct
            .asJava
          for {
            dsoRules <- store.getDsoRules()
            amuletRules <- store.getAmuletRules()
            cmd = dsoRules.exercise(
              _.exerciseDsoRules_UnhideRewardCouponsV2(
                amuletRules.contractId,
                new AmuletRules_UnhideRewardCouponsV2(cids, beneficiaries),
                controller,
              )
            )
            _ <- svTaskContext
              .connection(SpliceLedgerConnectionPriority.Low)
              .submit(
                Seq(store.key.svParty),
                Seq(store.key.dsoParty),
                cmd,
              )
              .noDedup
              .withSynchronizerId(dsoRules.domain)
              .yieldUnit()
          } yield TaskSuccess(
            s"unhid ${coupons.size} reward coupons for ${task.providerParty}"
          )
        }
    } yield result
  }
}

object UnhideRewardCouponV2Trigger {
  final case class Task(providerParty: PartyId) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("providerParty", _.providerParty))
  }
}

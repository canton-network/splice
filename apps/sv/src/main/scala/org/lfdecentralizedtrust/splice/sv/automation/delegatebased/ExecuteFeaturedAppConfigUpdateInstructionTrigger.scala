// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.instruction.FeaturedAppConfigUpdateInstruction
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}

class ExecuteFeaturedAppConfigUpdateInstructionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[
      SvDsoStore.FeaturedAppConfigUpdateInstructionWithRight
    ]
    with SvTaskBasedTrigger[SvDsoStore.FeaturedAppConfigUpdateInstructionWithRight] {
  private val store = svTaskContext.dsoStore

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[SvDsoStore.FeaturedAppConfigUpdateInstructionWithRight]] =
    store.listFeaturedAppConfigUpdateInstructionSample(
      PageLimit.tryCreate(context.config.parallelism)
    )

  override protected def completeTaskAsDsoDelegate(
      task: SvDsoStore.FeaturedAppConfigUpdateInstructionWithRight,
      controller: String,
  )(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ExecuteFeaturedAppConfigUpdateInstruction(
          task.instruction.contractId,
          task.featuredAppRightCid,
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
    } yield TaskSuccess(s"Executed update for provider ${task.instruction.payload.provider}")
  }

  /** Check whether a task has become stale and can be skipped.
    *
    * Note that a task can become stale for reasons other than 'completeTask' succeeding,
    * as there can be concurrent actions submitted to the ledger that make the task stale;
    * e.g., another party archiving a contract representing a request.
    */
  override protected def isStaleTask(
      task: SvDsoStore.FeaturedAppConfigUpdateInstructionWithRight
  )(implicit tc: TraceContext): Future[Boolean] =
    for {
      instruction <- store.multiDomainAcsStore.lookupContractById(
        FeaturedAppConfigUpdateInstruction.COMPANION
      )(task.instruction.contractId)
      featuredAppRight <- store.multiDomainAcsStore.lookupContractById(FeaturedAppRight.COMPANION)(
        task.featuredAppRightCid
      )
    } yield (instruction, featuredAppRight) match {
      case (Some(_), Some(_)) => false
      case _ => true
    }
}

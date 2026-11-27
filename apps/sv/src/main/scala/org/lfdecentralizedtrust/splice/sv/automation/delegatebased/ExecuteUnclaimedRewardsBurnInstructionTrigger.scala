// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskNoop,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.instruction.UnclaimedRewardsBurnInstruction
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.util.Contract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

import ExecuteUnclaimedRewardsBurnInstructionTrigger.Task

class ExecuteUnclaimedRewardsBurnInstructionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task]
    with SvTaskBasedTrigger[Task] {

  private val store = svTaskContext.dsoStore

  private val InstructionsLimit = PageLimit.tryCreate(100)

  override protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] = {
    val now = context.clock.now.toInstant
    store
      .listUnclaimedRewardsBurnInstructions(InstructionsLimit)
      .map(_.filter(task => now.isBefore(task.payload.expiresAt)))
      .flatMap(unexpired =>
        Future.traverse(unexpired) { task =>
          UnclaimedRewardsUtil
            .collectSufficientUnclaimedRewards(store, task.payload.amount)
            .map {
              case Left(UnclaimedRewardsUtil.Shortfall(missing)) =>
                logger.debug(
                  s"Not burning ${task.payload.amount} yet, the unclaimed rewards pool is short by $missing"
                )
                None
              case Right(_) => Some(task)
            }
        }
      )
      .map(_.flatten)
  }

  override protected def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    if (!context.clock.now.toInstant.isBefore(task.payload.expiresAt))
      Future.successful(true)
    else
      store.multiDomainAcsStore
        .lookupContractById(UnclaimedRewardsBurnInstruction.COMPANION)(task.contractId)
        .map(_.isEmpty)

  override protected def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val amount = task.payload.amount
    UnclaimedRewardsUtil.collectSufficientUnclaimedRewards(store, amount).flatMap {
      case Left(UnclaimedRewardsUtil.Shortfall(missing)) =>
        logger.info(
          s"Not burning $amount yet, the unclaimed rewards pool is short by $missing"
        )
        Future.successful(TaskNoop)
      case Right(unclaimedRewards) =>
        for {
          dsoRules <- store.getDsoRules()
          cmd = dsoRules.exercise(
            _.exerciseDsoRules_ExecuteUnclaimedRewardsBurnInstruction(
              task.contractId,
              unclaimedRewards.map(_.contractId).asJava,
              controller,
            )
          )
          _ <- svTaskContext
            .connection(SpliceLedgerConnectionPriority.Medium)
            .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
            .noDedup
            .yieldUnit()
        } yield TaskSuccess(s"burned $amount from the unclaimed rewards pool")
    }
  }
}

object ExecuteUnclaimedRewardsBurnInstructionTrigger {
  type Task =
    Contract[UnclaimedRewardsBurnInstruction.ContractId, UnclaimedRewardsBurnInstruction]
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskNoop,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.cryptohash.Hash
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.CalculateRewardsV2
import org.lfdecentralizedtrust.splice.http.v0.definitions.GetRewardAccountingRootHashResponse
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_StartProcessingRewardsV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ActionRequiringConfirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_StartProcessingRewardsV2
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.sv.automation.RewardProcessingMetrics
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

abstract class CalculateRewardsTriggerBase(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    scanConnectionF: Future[ScanConnection],
    isDryRun: Boolean,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[CalculateRewardsTriggerBase.Task] {

  import CalculateRewardsTriggerBase.*

  private val svParty = store.key.svParty
  private val dsoParty = store.key.dsoParty
  private val rewardMetrics = new RewardProcessingMetrics(context.metricsFactory)

  override def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] = for {
    // These are ordered by round, so we process the oldest first
    calculateRewards <- store.listCalculateRewardsV2()
    confirmedCids <- listConfirmedCalculateRewardsCids()
  } yield calculateRewards
    .filter(_.payload.dryRun == isDryRun)
    .filterNot(c => confirmedCids.contains(c.contractId))
    .map(Task(_))

  override def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val round = task.calculateRewards.payload.round.number
    getRootHash(round).flatMap {
      case None =>
        throw Status.FAILED_PRECONDITION
          .withDescription(
            s"Scan has not yet computed the root hash for CalculateRewardsV2 round $round."
          )
          .asRuntimeException()
      case Some(rootHash) =>
        val action = startProcessingRewardsAction(
          task.calculateRewards.contractId,
          rootHash,
        )
        for {
          queryResult <- store.lookupConfirmationByActionWithOffset(svParty, action)
          taskOutcome <- queryResult match {
            case QueryResult(_, Some(_)) =>
              Future.successful(TaskNoop)
            case QueryResult(offset, None) =>
              for {
                dsoRules <- store.getDsoRules()
                cmd = dsoRules.exercise(
                  _.exerciseDsoRules_ConfirmAction(
                    svParty.toProtoPrimitive,
                    action,
                  )
                )
                _ <- connection
                  .submit(
                    actAs = Seq(svParty),
                    readAs = Seq(dsoParty),
                    update = cmd,
                  )
                  .withDedup(
                    commandId = SpliceLedgerConnection.CommandId(
                      "org.lfdecentralizedtrust.splice.sv.createStartProcessingRewardsV2Confirmation",
                      Seq(svParty, dsoParty),
                      task.calculateRewards.contractId.contractId,
                    ),
                    deduplicationOffset = offset,
                  )
                  .yieldUnit()
                delay = java.time.Duration.between(
                  task.calculateRewards.payload.roundClosedAt,
                  context.clock.now.toInstant,
                )
                _ = rewardMetrics.calculateRewardsProcessingDelay.update(delay)(
                  MetricsContext.Empty.withExtraLabels("dryRun" -> isDryRun.toString)
                )
              } yield TaskSuccess(
                s"created confirmation for CalculateRewardsV2 round $round, processingDelay=$delay"
              )
          }
        } yield taskOutcome
    }
  }

  override def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractById(CalculateRewardsV2.COMPANION)(task.calculateRewards.contractId)
      .flatMap {
        case None => Future.successful(true)
        case Some(_) =>
          listConfirmedCalculateRewardsCids().map(
            _.contains(task.calculateRewards.contractId)
          )
      }

  private def listConfirmedCalculateRewardsCids()(implicit
      tc: TraceContext
  ): Future[Set[CalculateRewardsV2.ContractId]] =
    store.listConfirmationsByConfirmer(svParty).map { confirmations =>
      confirmations.iterator.flatMap { c =>
        c.payload.action match {
          case arc: ARC_AmuletRules =>
            arc.amuletRulesAction match {
              case crarc: CRARC_StartProcessingRewardsV2 =>
                Some(crarc.amuletRules_StartProcessingRewardsV2Value.calculateRewardsCid)
              case _ => None
            }
          case _ => None
        }
      }.toSet
    }

  private def getRootHash(round: Long)(implicit tc: TraceContext): Future[Option[Hash]] =
    scanConnectionF.flatMap(_.getRewardAccountingRootHash(round)).map {
      case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(ok) =>
        Some(new Hash(ok.rootHash))
      case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashUndetermined(_) =>
        None
      case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashCannotProvide(_) =>
        // TODO (#5623) replace with BFT read
        throw new RuntimeException(
          s"Scan cannot provide root hash for round $round"
        )
    }

  private def startProcessingRewardsAction(
      calculateRewardsCid: CalculateRewardsV2.ContractId,
      rootHash: Hash,
  ): ActionRequiringConfirmation =
    new ARC_AmuletRules(
      new CRARC_StartProcessingRewardsV2(
        new AmuletRules_StartProcessingRewardsV2(
          calculateRewardsCid,
          rootHash,
        )
      )
    )

}

class CalculateRewardsTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    scanConnectionF: Future[ScanConnection],
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CalculateRewardsTriggerBase(
      context,
      store,
      connection,
      scanConnectionF,
      isDryRun = false,
    )

class CalculateRewardsDryRunTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    scanConnectionF: Future[ScanConnection],
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CalculateRewardsTriggerBase(
      context,
      store,
      connection,
      scanConnectionF,
      isDryRun = true,
    )

object CalculateRewardsTriggerBase {

  final case class Task(
      calculateRewards: AssignedContract[
        CalculateRewardsV2.ContractId,
        CalculateRewardsV2,
      ]
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("round", _.calculateRewards.payload.round.number),
        param("dryRun", _.calculateRewards.payload.dryRun.toString.unquoted),
      )
  }
}

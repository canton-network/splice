// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.apache.pekko.stream.Materializer
import com.daml.metrics.api.MetricsContext
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.CalculateRewardsV2
import org.lfdecentralizedtrust.splice.scan.metrics.RewardComputationMetrics
import org.lfdecentralizedtrust.splice.scan.rewards.RewardComputationInputs
import org.lfdecentralizedtrust.splice.scan.store.{
  AppActivityStore,
  ScanAppRewardsStore,
  ScanRewardsReferenceStore,
}
import org.lfdecentralizedtrust.splice.store.{PageLimit, UpdateHistory}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Trigger that drives the CIP-0104 reward computation pipeline via
  * ScanAppRewardsStore.computeAndStoreRewards, which runs three
  * computation steps in one transaction:
  *   1. Aggregate activity totals from app activity records
  *   2. Compute reward totals (CC minting allowances with threshold filtering)
  *   3. Build the Merkle tree of batched reward hashes
  */
class RewardComputationTrigger(
    appRewardsStore: ScanAppRewardsStore,
    appActivityStore: AppActivityStore,
    rewardsReferenceStore: ScanRewardsReferenceStore,
    updateHistory: UpdateHistory,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[RewardComputationTrigger.Task] {

  private val rewardMetrics = new RewardComputationMetrics(context.metricsFactory)(
    MetricsContext(
      "current_migration_id" -> updateHistory.domainMigrationInfo.currentMigrationId.toString
    )
  )

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[RewardComputationTrigger.Task]] = {
    if (!updateHistory.isReady) {
      logger.debug("Waiting for UpdateHistory to become ready.")
      Future.successful(Seq.empty)
    } else
      for {
        // List active CalculateRewardsV2 contracts, ascending by round
        // and filter out
        // - rounds where the rewards are already computed
        // - rounds with incomplete activity
        candidates <- rewardsReferenceStore.listActiveCalculateRewardsV2(
          PageLimit.tryCreate(context.config.parallelism)
        )
        roundToContract = candidates.map(c => c.payload.round.number.toLong -> c.contractId).toMap
        candidateRounds = roundToContract.keys.toSeq.sorted
        computedRounds <- appRewardsStore.roundsWithComputedRewards(candidateRounds)
        afterComputedFilter = candidateRounds.filterNot(computedRounds.contains)
        earliestCompleteO <- appActivityStore.earliestRoundWithCompleteAppActivity()
        latestCompleteO <- appActivityStore.latestRoundWithCompleteAppActivity()
        eligible = (earliestCompleteO, latestCompleteO) match {
          case (Some(earliest), Some(latest)) =>
            afterComputedFilter.filter(r => r >= earliest && r <= latest)
          case _ => Seq.empty[Long]
        }

        // Look up OpenMiningRound for each eligible round and extract inputs.
        tasks <- Future.traverse(eligible)(r => buildTask(r, roundToContract(r)))
      } yield tasks
  }

  override protected def completeTask(
      task: RewardComputationTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    appRewardsStore
      .computeAndStoreRewards(task.roundNumber, task.batchSize, task.inputs)
      .map { summary =>
        rewardMetrics.record(summary)
        TaskSuccess(
          s"Computed rewards for round ${task.roundNumber}: " +
            s"${summary.activePartiesCount} active parties, " +
            s"${summary.activityRecordsCount} activity records, " +
            s"${summary.rewardedPartiesCount} rewarded parties, " +
            s"${summary.batchesCreatedCount} batches"
        )
      }

  private def buildTask(
      roundNumber: Long,
      contractId: CalculateRewardsV2.ContractId,
  )(implicit tc: TraceContext): Future[RewardComputationTrigger.Task] =
    rewardsReferenceStore.lookupOpenMiningRoundByNumber(roundNumber).map {
      case None =>
        throw Status.INTERNAL
          .withDescription(
            s"Round $roundNumber has a CalculateRewardsV2 contract and complete activity " +
              s"but its OpenMiningRound is not in the rewards reference store."
          )
          .asRuntimeException()
      case Some(contract) =>
        val (inputs, batchSize) =
          RewardComputationInputs.fromOpenMiningRound(contract.payload).getOrElse {
            throw Status.INTERNAL
              .withDescription(
                s"Round $roundNumber has a CalculateRewardsV2 contract but its " +
                  s"OpenMiningRound is missing rewardConfig or trafficPrice."
              )
              .asRuntimeException()
          }
        RewardComputationTrigger.Task(roundNumber, batchSize, inputs, contractId)
    }

  override protected def isStaleTask(
      task: RewardComputationTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] =
    for {
      contractGone <- rewardsReferenceStore.multiDomainAcsStore
        .lookupContractById(CalculateRewardsV2.COMPANION)(task.calculateRewardsId)
        .map(_.isEmpty)
      alreadyComputed <- appRewardsStore
        .roundsWithComputedRewards(Seq(task.roundNumber))
        .map(_.nonEmpty)
    } yield contractGone || alreadyComputed

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync() :+
      SyncCloseable("RewardComputationMetrics", rewardMetrics.close())
}

object RewardComputationTrigger {

  final case class Task(
      roundNumber: Long,
      batchSize: Int,
      inputs: RewardComputationInputs,
      calculateRewardsId: CalculateRewardsV2.ContractId,
  ) extends PrettyPrinting {
    import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("roundNumber", _.roundNumber),
        param("calculateRewardsId", _.calculateRewardsId),
      )
  }
}

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
import org.lfdecentralizedtrust.splice.scan.automation.PruneRewardAccountingTrigger.Task
import org.lfdecentralizedtrust.splice.scan.metrics.RewardAccountingPruningMetrics
import org.lfdecentralizedtrust.splice.scan.store.{ScanAppRewardsStore, ScanRewardsReferenceStore}
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Periodically deletes reward-accounting data that is no longer needed, one
  * round at a time. Prunes the following
  *   - Computed values from six reward-accounting tables in ScanAppRewardsStore:
  *     `app_activity_party_totals`
  *     `app_activity_round_totals`
  *     `app_reward_party_totals`
  *     `app_reward_round_totals`
  *     `app_reward_batch_hashes`
  *     `app_reward_root_hashes`
  *
  *   - Archived contracts from `ScanRewardsReferenceStore` archive table
  *     which archived <= the archival time of the OpenMiningRound
  *
  * A round only becomes eligible for pruning once
  *   - None of its `OpenMiningRound`, `CalculateRewardsV2`, or `ProcessRewardsV2` contracts,
  *     nor those of any lower round, remain active.
  *   - The verdict ingestion has moved past sufficiently,
  *     such that lookup of data for a subsequent round won't be affected by the pruning.
  *   - The archival time of the round is older than the configured `retentionPeriod`.
  */
class PruneRewardAccountingTrigger(
    appRewardsStore: ScanAppRewardsStore,
    rewardsReferenceStore: ScanRewardsReferenceStore,
    verdictStore: DbScanVerdictStore,
    updateHistory: UpdateHistory,
    retentionPeriod: NonNegativeFiniteDuration,
    triggerContext: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task] {

  override protected def context: TriggerContext = triggerContext.copy(
    config = triggerContext.config.copy(
      parallelism = 1
    )
  )

  private val pruningMetrics = new RewardAccountingPruningMetrics(context.metricsFactory)(
    MetricsContext(
      "current_migration_id" -> updateHistory.domainMigrationId.toString
    )
  )

  override def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] =
    if (!updateHistory.isReady) {
      logger.debug("Waiting for UpdateHistory to become ready.")
      Future.successful(Seq.empty)
    } else
      verdictStore.lastIngestedRecordTime match {
        case None =>
          logger.debug("Skipping pruning as no verdict has been ingested yet.")
          Future.successful(Seq.empty)
        case Some(lastIngestedRecordTime) =>
          rewardsReferenceStore
            .lookupPrunableRewardRound(
              lastIngestedRecordTime,
              context.clock.now,
              retentionPeriod,
            )
            .map {
              case Some(roundNumber) => Seq(Task(roundNumber))
              case None => Seq.empty
            }
      }

  override def completeTask(task: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      /* The two deletes happen on different stores, and the deletes are being
       * done in independent transactions.
       * Because both are idempotent operations and re-running either for the
       * same or an older round is a no-op.
       * If pruning of either store fails, the task will be retried.
       *
       * Even if the task is abandoned, a subsequent invocation of the trigger
       * will work because the task creation depends only on the contents of
       * rewardsReferenceStore which is deleted after the appRewardsStore
       *
       * Also here it is assumed that ScanAppRewardsStore only has the data for
       * rounds for which we have data in ScanRewardsReferenceStore. Which is a
       * fair assumption as the reward calculations require the data to be
       * present in ScanRewardsReferenceStore for that round.
       */
      summary <- appRewardsStore.deleteRewardAccountingDataForRound(task.roundNumber)
      deletedArchiveRows <- rewardsReferenceStore.pruneArchivedUpToRound(task.roundNumber)
    } yield {
      pruningMetrics.record(task.roundNumber, summary, deletedArchiveRows)
      TaskSuccess(
        s"Pruned reward accounting data for round ${task.roundNumber}: " +
          s"removed $deletedArchiveRows archived rows; and $summary."
      )
    }

  override def isStaleTask(task: Task)(implicit tc: TraceContext): Future[Boolean] =
    rewardsReferenceStore.lookupArchivedAtForOpenMiningRound(task.roundNumber).map(_.isEmpty)

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync() :+
      SyncCloseable("RewardAccountingPruningMetrics", pruningMetrics.close())
}

object PruneRewardAccountingTrigger {
  final case class Task(roundNumber: Long) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("round", _.roundNumber)
      )
  }
}

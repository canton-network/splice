// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.scan.automation.PruneRewardAccountingTrigger.Task
import org.lfdecentralizedtrust.splice.scan.store.{ScanAppRewardsStore, ScanRewardsReferenceStore}
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import com.digitalasset.canton.data.CantonTimestamp
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
  * A round only becomes eligible for pruning once none of its `OpenMiningRound`,
  * `CalculateRewardsV2`, or `ProcessRewardsV2` contracts -- nor those of any
  * lower round -- remain active. AND the verdict ingestion has moved past the
  * `record_time` of OpenMiningRound's archival.
  */
class PruneRewardAccountingTrigger(
    appRewardsStore: ScanAppRewardsStore,
    rewardsReferenceStore: ScanRewardsReferenceStore,
    verdictStore: DbScanVerdictStore,
    updateHistory: UpdateHistory,
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

  override def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] =
    if (!updateHistory.isReady) {
      logger.debug("Waiting for UpdateHistory to become ready.")
      Future.successful(Seq.empty)
    } else
      rewardsReferenceStore.lookupLowestPrunableArchivedRewardRound().flatMap {
        case None =>
          Future.successful(Seq.empty)
        case Some(roundNumber) =>
          // For ScanRewardsReferenceStore the earliest archived_at row act as
          // an indicator of the "ingestion start", and therefore we need to ensure
          // that we keep at least one archived row in the store with record_time lower than
          // the lastIngestedRecordTime's oldest active round's `openAt` time.
          //
          // A simple way to ensure this is to compare the `openAt` with the roundNumber + 1 archived_at.
          verdictStore.lastIngestedRecordTime match {
            case None =>
              logger.debug("Skipping pruning as no verdict has been ingested yet.")
              Future.successful(Seq.empty)
            case Some(lastIngestedRecordTime) =>
              rewardsReferenceStore
                .lookupActiveOpenMiningRounds(Seq(lastIngestedRecordTime))
                .flatMap(_.get(lastIngestedRecordTime) match {
                  case None =>
                    logger.warn(
                      s"We should never hit this, as we should always have an active OpenMiningRound" +
                        s"as of the last ingested verdict record time ($lastIngestedRecordTime)."
                    )
                    Future.successful(Seq.empty)
                  case Some((activeRoundNumber, _)) =>
                    for {
                      activeRoundO <- rewardsReferenceStore.lookupOpenMiningRoundByNumber(
                        activeRoundNumber
                      )
                      archivedAtNextO <- rewardsReferenceStore.lookupArchivedAtForOpenMiningRound(
                        roundNumber + 1
                      )
                    } yield (activeRoundO, archivedAtNextO) match {
                      case (Some(activeRound), Some(archivedAtNext)) =>
                        if (
                          CantonTimestamp
                            .assertFromInstant(activeRound.payload.opensAt) > archivedAtNext
                        ) {
                          Seq(Task(roundNumber))
                        } else {
                          logger.debug(
                            s"Skipping pruning of round $roundNumber as the ingestion's currently active round " +
                              s"$activeRoundNumber opened at ${activeRound.payload.opensAt}, which is not yet " +
                              s"past round ${roundNumber + 1}'s archivedAt ($archivedAtNext)."
                          )
                          Seq.empty
                        }
                      case (_, None) =>
                        logger.debug(
                          s"Skipping pruning of round $roundNumber as round ${roundNumber + 1} has not archived yet."
                        )
                        Seq.empty
                      case (None, _) =>
                        logger.warn(
                          s"This should never happen, could not resolve OpenMiningRound $activeRoundNumber."
                        )
                        Seq.empty
                    }
                })
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
       * Also here it is assumed that ScanAppRewardsStore only has the data for
       * rounds for which we have data in ScanRewardsReferenceStore. Which is a
       * fair assumption as the reward calculations require the data to be
       * present in ScanRewardsReferenceStore for that round.
       */
      summary <- appRewardsStore.deleteRewardAccountingDataForRound(task.roundNumber)
      deletedArchiveRows <- rewardsReferenceStore.pruneArchivedDataForRound(task.roundNumber)
    } yield TaskSuccess(
      s"Pruned reward accounting data for round ${task.roundNumber}: " +
        s"removed $deletedArchiveRows archived rows; and $summary."
    )

  override def isStaleTask(task: Task)(implicit tc: TraceContext): Future[Boolean] =
    rewardsReferenceStore.lookupArchivedAtForOpenMiningRound(task.roundNumber).map(_.isEmpty)
}

object PruneRewardAccountingTrigger {
  final case class Task(roundNumber: Long) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("round", _.roundNumber)
      )
  }
}

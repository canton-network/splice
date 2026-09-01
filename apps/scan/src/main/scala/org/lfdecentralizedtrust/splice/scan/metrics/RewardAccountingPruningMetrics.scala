// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.metrics

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory, Meter}
import com.daml.metrics.api.MetricQualification.{Debug, Traffic}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore.RewardAccountingPruneSummary

class RewardAccountingPruningMetrics(metricsFactory: LabeledMetricsFactory)(implicit
    metricsContext: MetricsContext
) extends AutoCloseable {
  private val prefix: MetricName =
    SpliceMetrics.MetricsPrefix :+ "scan" :+ "reward_accounting_pruning"

  // In normal operation this advances by one round per tick, so it stalling is
  // the signal that pruning stopped making progress.
  val prunedRound: Gauge[Long] = metricsFactory.gauge(
    MetricInfo(
      name = prefix :+ "pruned_round",
      summary = "The round whose reward accounting data was deleted most recently",
      qualification = Debug,
    ),
    -1L,
  )(metricsContext)

  private val deletedRows: Meter = metricsFactory.meter(
    MetricInfo(
      name = prefix :+ "deleted_rows",
      summary = "Number of reward accounting rows deleted by pruning",
      description = "The total number of rows deleted by the reward accounting pruning, " +
        "labeled with the table they were deleted from.",
      qualification = Traffic,
      labelsWithDescription = Map("table" -> "The table the rows were deleted from"),
    )
  )(metricsContext)

  private def markDeletedRows(table: String, rows: Long): Unit =
    deletedRows.mark(rows)(metricsContext.withExtraLabels("table" -> table))

  def record(
      roundNumber: Long,
      summary: RewardAccountingPruneSummary,
      deletedArchiveRows: Long,
  ): Unit = {
    markDeletedRows("app_activity_party_totals", summary.activityPartyTotals)
    markDeletedRows("app_activity_round_totals", summary.activityRoundTotals)
    markDeletedRows("app_reward_party_totals", summary.rewardPartyTotals)
    markDeletedRows("app_reward_round_totals", summary.rewardRoundTotals)
    markDeletedRows("app_reward_batch_hashes", summary.batchHashes)
    markDeletedRows("app_reward_root_hashes", summary.rootHashes)
    markDeletedRows("scan_rewards_reference_store_archived", deletedArchiveRows)
    prunedRound.updateValue(roundNumber)
  }

  override def close(): Unit = {
    prunedRound.close()
  }
}

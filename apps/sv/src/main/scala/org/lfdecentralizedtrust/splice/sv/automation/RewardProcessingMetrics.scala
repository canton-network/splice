// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricHandle.{LabeledMetricsFactory, Meter, Timer}
import com.daml.metrics.api.MetricQualification.{Latency, Traffic}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class RewardProcessingMetrics(metricsFactory: LabeledMetricsFactory)(
    metricsContext: MetricsContext
) {

  private val prefix: MetricName = SpliceMetrics.MetricsPrefix

  val calculateRewardsProcessingDelay: Timer =
    metricsFactory.timer(
      MetricInfo(
        name = prefix :+ "calculate_rewards_v2" :+ "processing_delay",
        summary = "Delay between round close and CalculateRewardsV2 confirmation creation",
        description =
          "This metric captures the time it took between the closing of a round, and this SV's confirmation for the CalculateRewardsV2 contract's processing. Labeled with dryRun.",
        qualification = Latency,
      )
    )(metricsContext)

  val processRewardsProcessingDelay: Timer =
    metricsFactory.timer(
      MetricInfo(
        name = prefix :+ "process_rewards_v2" :+ "processing_delay",
        summary = "Delay between round close and ProcessRewardsV2 processing",
        description =
          "This metric captures the time it took between the closing of a round, and this SV's processing of a ProcessRewardsV2 contract for that round. Labeled with dryRun.",
        qualification = Latency,
      )
    )(metricsContext)

  val calculateRewardsRootHashBftReads: Meter =
    metricsFactory.meter(
      MetricInfo(
        name = prefix :+ "calculate_rewards_v2" :+ "root_hash_bft_reads",
        summary = "Count of BFT reads of the reward-accounting root-hash",
        description =
          "This metric counts the BFT reads of the reward-accounting root-hash performed by the CalculateRewardsV2 trigger, i.e., the cases where this SV's own Scan could not provide the root-hash and it had to be obtained via a BFT read against peer Scans. Labeled with dryRun.",
        qualification = Traffic,
      )
    )(metricsContext)

  val processRewardsBatchBftReads: Meter =
    metricsFactory.meter(
      MetricInfo(
        name = prefix :+ "process_rewards_v2" :+ "batch_bft_reads",
        summary = "Count of BFT reads of the reward-accounting batch",
        description =
          "This metric counts the BFT reads of the reward-accounting batch performed by the ProcessRewardsV2 trigger, i.e., the cases where this SV's own Scan could not provide the batch and it had to be obtained via a BFT read against peer Scans. Labeled with dryRun.",
        qualification = Traffic,
      )
    )(metricsContext)
}

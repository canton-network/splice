// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.daml.metrics.api.{MetricInfo, MetricName}
import com.daml.metrics.api.MetricHandle.{LabeledMetricsFactory, Timer}
import com.daml.metrics.api.MetricQualification.Latency
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

class RewardProcessingMetrics(metricsFactory: LabeledMetricsFactory) {

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
    )

  val processRewardsProcessingDelay: Timer =
    metricsFactory.timer(
      MetricInfo(
        name = prefix :+ "process_rewards_v2" :+ "processing_delay",
        summary = "Delay between round close and ProcessRewardsV2 processing",
        description =
          "This metric captures the time it took between the closing of a round, and this SV's processing of a ProcessRewardsV2 contract for that round. Labeled with dryRun.",
        qualification = Latency,
      )
    )
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.metrics

import com.daml.metrics.api.MetricHandle.{Counter, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Traffic
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

/** Metrics for the Scan HTTP query API.
  */
class ScanHttpApiMetrics(metricsFactory: LabeledMetricsFactory) {
  import ScanHttpApiMetrics.*

  private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "scan_api"

  /** A single counter for the whole ACS-snapshot query family of endpoints, with labels for which optional filters were supplied.
    */
  val snapshotQueryRequests: Counter = metricsFactory.counter(
    MetricInfo(
      name = prefix :+ "snapshot_query_requests",
      summary =
        "Count of ACS-snapshot query requests, labelled by which optional filters were supplied.",
      qualification = Traffic,
      labelsWithDescription = Map(
        LabelNames.Operation -> "OpenAPI operation id",
        LabelNames.PartyFilter -> "Whether a party-id filter was supplied: true/false/not_applicable.",
        LabelNames.TemplateFilter -> "Whether a template filter was supplied: true/false/not_applicable.",
        LabelNames.RecordTimeMatch -> "How the record time was matched: exact or at_or_before.",
        LabelNames.AsOfRound -> "Whether as_of_round was supplied: default/explicit/not_applicable.",
      ),
    )
  )
}

object ScanHttpApiMetrics {

  object LabelNames {
    val Operation = "operation"
    val PartyFilter = "party_filter"
    val TemplateFilter = "template_filter"
    val RecordTimeMatch = "record_time_match"
    val AsOfRound = "as_of_round"
  }

  /** Presence of an optional filter, or that the filter does not exist for this operation. */
  sealed abstract class Presence(val label: String)
  object Presence {
    case object Present extends Presence("true")
    case object Absent extends Presence("false")
    case object NotApplicable extends Presence("not_applicable")

    def apply(supplied: Boolean): Presence = if (supplied) Present else Absent
  }

  /** Whether as_of_round applies to this operation and, if so, whether it was set explicitly. */
  sealed abstract class AsOfRound(val label: String)
  object AsOfRound {
    case object Default extends AsOfRound("default")
    case object Explicit extends AsOfRound("explicit")
    case object NotApplicable extends AsOfRound("not_applicable")
  }

  final case class SnapshotQueryLabels(
      operation: String,
      partyFilter: Presence,
      templateFilter: Presence,
      atOrBefore: Boolean,
      asOfRound: AsOfRound,
  ) {
    def context: MetricsContext = MetricsContext(
      LabelNames.Operation -> operation,
      LabelNames.PartyFilter -> partyFilter.label,
      LabelNames.TemplateFilter -> templateFilter.label,
      LabelNames.RecordTimeMatch -> (if (atOrBefore) "at_or_before" else "exact"),
      LabelNames.AsOfRound -> asOfRound.label,
    )
  }
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.time.Clock
import java.time.Instant
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules

object SwitchOverTimes {

  val NoFeaturedAppChoiceContext = "no-featured-app-choice-context"

  /** Rounds opening at or after this time use the CIP-104 amended (TBAR v1.1)
    * activity-record computation.
    */
  val UseCip104AmendedCalculationAfter = "cip-104/use-amended-calculation-after"

  /** The default switch-over times used when founding a new DSO. */
  val defaultSvOperationsSwitchOverTimes: Map[String, CantonTimestamp] = Map(
    NoFeaturedAppChoiceContext -> CantonTimestamp.MinValue,
    UseCip104AmendedCalculationAfter -> CantonTimestamp.MinValue,
  )

  private def hasSwitchedOverAsOf(
      asOf: CantonTimestamp,
      times: java.util.Optional[java.util.Map[String, Instant]],
      key: String,
  ): Boolean = {
    val timeO = Option(times.orElse(java.util.Map.of()).get(key))
    timeO.fold(false)(time => asOf >= CantonTimestamp.assertFromInstant(time))
  }

  /** Whether the switch-over for `key` configured in `dsoRules` happened at or before `asOf`. */
  def hasSwitchedOverAsOf(asOf: CantonTimestamp, dsoRules: DsoRules, key: String): Boolean =
    hasSwitchedOverAsOf(asOf, dsoRules.config.svOperationsSwitchOverTimes, key)

  private def shouldSwitchOver(clock: Clock, dsoRules: DsoRules, key: String): Boolean =
    hasSwitchedOverAsOf(clock.now, dsoRules, key)

  def omitFeaturedAppRightInChoiceContext(clock: Clock, dsoRules: DsoRules) =
    shouldSwitchOver(clock, dsoRules, NoFeaturedAppChoiceContext)
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.scan.automation.PruneUpdateHistoryTrigger.{prunable, startOf}
import org.scalatest.wordspec.AnyWordSpec

import java.time.LocalDate

class PruneUpdateHistoryTriggerTest extends AnyWordSpec with BaseTest {

  private val day1 = LocalDate.of(2026, 11, 1)
  private val days = (0L to 9L).map(day1.plusDays).toSet
  private val endOfDay1 = startOf(day1.plusDays(1))
  private val muchLater = startOf(day1.plusDays(30))

  "prunable" should {

    "be nothing while bulk storage has committed no updates" in {
      prunable(days, Some(muchLater), CantonTimestamp.MinValue) shouldBe None
    }

    "be nothing with an unlimited analyzable time window" in {
      prunable(days, None, muchLater) shouldBe None
    }

    "be nothing while the first day is not fully committed to bulk storage" in {
      prunable(days, Some(muchLater), endOfDay1.minusSeconds(1)) shouldBe None
    }

    "be nothing while the first day ends inside the analyzable time window" in {
      prunable(days, Some(endOfDay1.minusSeconds(1)), muchLater) shouldBe None
    }

    "be the oldest day once it is committed and before the analyzable time window" in {
      prunable(days, Some(endOfDay1), endOfDay1) shouldBe Some(day1)
    }

    "be the oldest day when several days are prunable" in {
      prunable(days, Some(muchLater), muchLater) shouldBe Some(day1)
    }

    "be nothing without partitions" in {
      prunable(Set.empty, Some(muchLater), muchLater) shouldBe None
    }
  }
}

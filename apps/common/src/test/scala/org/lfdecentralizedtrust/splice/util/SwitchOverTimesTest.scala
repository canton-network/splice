// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.cometbft.CometBftConfigLimits
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.{
  DsoDecentralizedSynchronizerConfig,
  SynchronizerNodeConfigLimits,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{DsoRules, DsoRulesConfig}
import org.scalatest.wordspec.AnyWordSpec

import java.util.{Collections, Optional}
import scala.jdk.CollectionConverters.*

class SwitchOverTimesTest extends AnyWordSpec with BaseTest {

  private val switchOverTime = CantonTimestamp.ofEpochSecond(1000)

  private def mkDsoRules(switchOverTimes: Map[String, CantonTimestamp]): DsoRules = {
    val synchronizerId = "domain::id"
    new DsoRules(
      "dso::namespace",
      1,
      Collections.emptyMap(),
      Collections.emptyMap(),
      "dso::namespace",
      new DsoRulesConfig(
        1,
        1,
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new SynchronizerNodeConfigLimits(new CometBftConfigLimits(1, 1, 1, 1, 1)),
        1,
        new DsoDecentralizedSynchronizerConfig(
          Collections.emptyMap(),
          synchronizerId,
          synchronizerId,
        ),
        Optional.empty(),
        Optional.empty(), // voteCooldownTime
        Optional.empty(), // nextScheduledLogicalSynchronizerUpgrade
        Optional.of(
          switchOverTimes.view.mapValues(_.toInstant).toMap.asJava
        ), // svOperationsSwitchOverTimes
      ),
      Collections.emptyMap(),
      true,
    )
  }

  "serveVerdictRoundNumber" should {
    val dsoRules =
      mkDsoRules(Map(SwitchOverTimes.AlwaysServeVerdictRoundNumber -> switchOverTime))

    "be false for verdicts recorded before the switch-over time" in {
      SwitchOverTimes.serveVerdictRoundNumber(
        switchOverTime.minusSeconds(1),
        dsoRules,
      ) shouldBe false
    }

    "be true for verdicts recorded at the switch-over time" in {
      SwitchOverTimes.serveVerdictRoundNumber(switchOverTime, dsoRules) shouldBe true
    }

    "be true for verdicts recorded after the switch-over time" in {
      SwitchOverTimes.serveVerdictRoundNumber(
        switchOverTime.plusSeconds(1),
        dsoRules,
      ) shouldBe true
    }

    "be false when no switch-over time is configured" in {
      SwitchOverTimes.serveVerdictRoundNumber(
        switchOverTime.plusSeconds(1),
        mkDsoRules(Map.empty),
      ) shouldBe false
    }
  }
}

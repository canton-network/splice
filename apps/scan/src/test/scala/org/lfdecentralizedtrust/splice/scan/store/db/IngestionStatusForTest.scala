// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.BaseTest
import org.lfdecentralizedtrust.splice.scan.store.ActivityRecordMetaStore.RoundIngestionStatus
import org.lfdecentralizedtrust.splice.scan.store.db.DbActivityRecordMetaStore.ingestionStatusFor
import org.scalatest.wordspec.AnyWordSpec

class IngestionStatusForTest extends AnyWordSpec with BaseTest {

  "ingestionStatusFor" should {

    "return CannotProvide when meta absent and isFirstSv=false" in {
      ingestionStatusFor(isFirstSv = false, earliestIngested = None, roundNumber = 5L) shouldBe
        RoundIngestionStatus.CannotProvide
    }

    "return Undetermined when meta absent and isFirstSv=true" in {
      ingestionStatusFor(isFirstSv = true, earliestIngested = None, roundNumber = 5L) shouldBe
        RoundIngestionStatus.Undetermined
    }

    "return CannotProvide when meta present and round is below earliest" in {
      ingestionStatusFor(isFirstSv = false, earliestIngested = Some(10L), roundNumber = 5L) shouldBe
        RoundIngestionStatus.CannotProvide
    }

    "return CannotProvide when meta present and round equals earliest (boundary)" in {
      ingestionStatusFor(
        isFirstSv = false,
        earliestIngested = Some(10L),
        roundNumber = 10L,
      ) shouldBe
        RoundIngestionStatus.CannotProvide
    }

    "return Undetermined when meta present and round is beyond earliest" in {
      ingestionStatusFor(
        isFirstSv = false,
        earliestIngested = Some(10L),
        roundNumber = 15L,
      ) shouldBe
        RoundIngestionStatus.Undetermined
    }

    "ignore isFirstSv when meta is present" in {
      ingestionStatusFor(isFirstSv = true, earliestIngested = Some(10L), roundNumber = 5L) shouldBe
        RoundIngestionStatus.CannotProvide
      ingestionStatusFor(isFirstSv = true, earliestIngested = Some(10L), roundNumber = 15L) shouldBe
        RoundIngestionStatus.Undetermined
    }
  }
}

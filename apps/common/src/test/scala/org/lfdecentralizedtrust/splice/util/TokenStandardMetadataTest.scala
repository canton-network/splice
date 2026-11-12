// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpec

class TokenStandardMetadataTest extends AnyWordSpec with BaseTest {
  private def synchronizerId(memo: String) =
    TokenStandardMetadata.trafficPurchaseMemoSynchronizerId(memo)

  "trafficPurchaseMemoSynchronizerId" should {
    "return the synchronizer id of a traffic purchase memo" in {
      synchronizerId(
        "splice/memo:member-id=PAR::p::1220ab&synchronizer-id=global::1220cd&migration-id=0"
      ) shouldBe Some("global::1220cd")
      synchronizerId("splice/memo:migration-id=0&synchronizer-id=dedicated::1220ef") shouldBe
        Some("dedicated::1220ef")
    }

    "return None for any other memo" in {
      synchronizerId("") shouldBe None
      synchronizerId("payment for lunch") shouldBe None
      synchronizerId("synchronizer-id=dedicated::1220ef&migration-id=0") shouldBe None
      synchronizerId("splice/memo:member-id=PAR::p::1220ab&migration-id=0") shouldBe None
      synchronizerId("splice/memo:synchronizer-id=a=b&migration-id=0") shouldBe None
    }

    "return None for an empty synchronizer id" in {
      synchronizerId("splice/memo:synchronizer-id=&migration-id=0") shouldBe None
    }

    // The Daml code rejects these memos, so it doesn't matter which synchronizer id they give.
    "not validate the rest of the memo" in {
      synchronizerId("splice/memo:synchronizer-id=dedicated::1220ef&migration-id=0&") shouldBe
        Some("dedicated::1220ef")
      synchronizerId(
        "splice/memo:synchronizer-id=first::1220aa&synchronizer-id=second::1220bb&migration-id=0"
      ) shouldBe Some("first::1220aa")
    }
  }
}

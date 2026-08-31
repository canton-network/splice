// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.BaseTest
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.QueryAcsSnapshotPaginationToken
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.QueryAcsSnapshotPaginationToken.RowIdQueryAcsSnapshotPaginationToken
import org.scalatest.wordspec.AnyWordSpec
import scala.util.Try

class QueryAcsSnapshotPaginationTokenTest extends AnyWordSpec with BaseTest {

  "RowIdQueryAcsSnapshotPaginationToken" should {

    "encode to base64 and decode back" in {
      val token = RowIdQueryAcsSnapshotPaginationToken(42L)
      val encoded = token.encodeToBase64
      val decoded = QueryAcsSnapshotPaginationToken.tryDecodeFromBase64(encoded)
      decoded shouldBe token
    }

    "produce different encoded values for different row ids" in {
      val token1 = RowIdQueryAcsSnapshotPaginationToken(1L)
      val token2 = RowIdQueryAcsSnapshotPaginationToken(2L)
      token1.encodeToBase64 should not equal token2.encodeToBase64
    }
  }

  "QueryAcsSnapshotPaginationToken.decodeFromBase64" should {

    "return Left for an invalid base64 string" in {
      val result = Try(QueryAcsSnapshotPaginationToken.tryDecodeFromBase64("not-valid-base64!!!"))
      result.isFailure should be(true)
    }

    "return Left for valid base64 but invalid JSON content" in {
      val encoded = java.util.Base64.getEncoder.encodeToString("not-a-long".getBytes("UTF-8"))
      val result = Try(QueryAcsSnapshotPaginationToken.tryDecodeFromBase64(encoded))
      result.isFailure should be(true)
    }

    "return Left for valid base64 with JSON object instead of long" in {
      val encoded =
        java.util.Base64.getEncoder.encodeToString("""{"after": 42}""".getBytes("UTF-8"))
      val result = Try(QueryAcsSnapshotPaginationToken.tryDecodeFromBase64(encoded))
      result.isFailure should be(true)
    }
  }

}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock as gl
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
import com.digitalasset.canton.BaseTest
import java.time.Instant
import org.scalatest.wordspec.AnyWordSpec

// ATTENTION: if this test fails, make sure that
// `DbScanStore.listGovernanceLocksByKey` still finds locks stored by earlier
// releases before you update the expected strings.
//
// Scan looks up governance locks by key by comparing the stored
// `create_arguments->'specification'` with the JSON of a
// `GovernanceLockSpecification`, using jsonb equality. The stored value was
// written by the release that ingested the lock, so if the JSON of a
// specification changes between releases, the lookup stops finding older
// locks.
//
// This test pins the current JSON of every kind of specification, as a
// tripwire for such changes.
//
// A typical break: a new optional field is encoded as `"field": null`, while
// values stored by earlier releases lack the field entirely, so they are no
// longer jsonb-equal.
class FreezeGovernanceLockJsonTest extends AnyWordSpec with BaseTest {
  private val provider = "provider::1220aaaa"
  private val featuredApp = new gl.governancelockkind.GLK_FeaturedApp(provider)
  private val provisionalFeaturedApp =
    new gl.governancelockkind.GLK_ProvisionalFeaturedApp(provider)
  private val superValidator =
    new gl.governancelockkind.GLK_SuperValidatorRightsOwner("sv1")

  // Default vesting periods per lock kind, in microseconds.
  // 0 days
  private val provisionalFeaturedAppVestingPeriod = new RelTime(0L)
  // 60 days
  private val featuredAppVestingPeriod = new RelTime(5_184_000_000_000L)
  // 365.25 days (365 days and 6 hours)
  private val superValidatorVestingPeriod = new RelTime(31_557_600_000_000L)
  private val endTime = Instant.parse("2026-10-27T12:34:56.123456Z")

  private def fullLock(kind: gl.GovernanceLockKind): String =
    AcsJdbcTypes
      .payloadJsonFromDefinedDataType(
        new gl.governancelockspecification.GLS_FullLock(
          new gl.FullLockSpecification(kind)
        )
      )
      .noSpaces

  private def vestingLock(
      kind: gl.GovernanceLockKind,
      vestingPeriod: RelTime,
  ): String =
    AcsJdbcTypes
      .payloadJsonFromDefinedDataType(
        new gl.governancelockspecification.GLS_VestingLock(
          new gl.VestingLockSpecification(kind, vestingPeriod, endTime)
        )
      )
      .noSpaces

  "The JSON of a governance lock specification" should {
    "not change for a featured app full lock" in {
      fullLock(featuredApp) shouldBe
        """{"tag":"GLS_FullLock","value":{"kind":{"tag":"GLK_FeaturedApp","value":{"provider":"provider::1220aaaa"}}}}"""
    }

    "not change for a provisional featured app full lock" in {
      fullLock(provisionalFeaturedApp) shouldBe
        """{"tag":"GLS_FullLock","value":{"kind":{"tag":"GLK_ProvisionalFeaturedApp","value":{"provider":"provider::1220aaaa"}}}}"""
    }

    "not change for a super validator full lock" in {
      fullLock(superValidator) shouldBe
        """{"tag":"GLS_FullLock","value":{"kind":{"tag":"GLK_SuperValidatorRightsOwner","value":{"svName":"sv1"}}}}"""
    }

    "not change for a featured app vesting lock" in {
      vestingLock(featuredApp, featuredAppVestingPeriod) shouldBe
        """{"tag":"GLS_VestingLock","value":{"kind":{"tag":"GLK_FeaturedApp","value":{"provider":"provider::1220aaaa"}},"vestingPeriod":{"microseconds":5184000000000},"endTime":"2026-10-27T12:34:56.123456Z"}}"""
    }

    "not change for a provisional featured app vesting lock" in {
      vestingLock(
        provisionalFeaturedApp,
        provisionalFeaturedAppVestingPeriod,
      ) shouldBe
        """{"tag":"GLS_VestingLock","value":{"kind":{"tag":"GLK_ProvisionalFeaturedApp","value":{"provider":"provider::1220aaaa"}},"vestingPeriod":{"microseconds":0},"endTime":"2026-10-27T12:34:56.123456Z"}}"""
    }

    "not change for a super validator vesting lock" in {
      vestingLock(superValidator, superValidatorVestingPeriod) shouldBe
        """{"tag":"GLS_VestingLock","value":{"kind":{"tag":"GLK_SuperValidatorRightsOwner","value":{"svName":"sv1"}},"vestingPeriod":{"microseconds":31557600000000},"endTime":"2026-10-27T12:34:56.123456Z"}}"""
    }
  }
}

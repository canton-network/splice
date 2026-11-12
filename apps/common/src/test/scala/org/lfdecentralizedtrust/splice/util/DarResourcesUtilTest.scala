// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.BaseTest
import com.digitalasset.daml.lf.data.Ref.PackageId
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.scalatest.wordspec.AnyWordSpec

class DarResourcesUtilTest extends AnyWordSpec with BaseTest {

  private val wallet = DarResources.wallet.latest
  // not a dependency of anything
  private val unrelatedPackageId = "00" * 32

  "DarResource.identification" should {
    "ignore the resource path" in {
      wallet.copy(path = "elsewhere.dar").identification shouldBe wallet.identification
    }

    "distinguish dependencies and upgraded package ids" in {
      wallet
        .copy(dependencyPackageIds = wallet.dependencyPackageIds + unrelatedPackageId)
        .identification should not be wallet.identification
      wallet
        .copy(metadata =
          wallet.metadata.copy(upgradedPackageId = Some(PackageId.assertFromString(unrelatedPackageId)))
        )
        .identification should not be wallet.identification
    }
  }

  "getRequiredPackageVersions" should {
    "list each DAR once, even if the latest one is also listed under its versioned path" in {
      for {
        pkg <- DarResources.corePackageResources
        enableUnsupportedDarsUnvetting <- Seq(false, true)
      } {
        val required = DarResourcesUtil.getRequiredPackageVersions(
          pkg.latest.metadata.name,
          pkg.latest.metadata.version,
          enableUnsupportedDarsUnvetting,
        )
        clue(s"${pkg.latest.metadata.name}, unvetting $enableUnsupportedDarsUnvetting") {
          required.map(_.identification).distinct should have size required.size.toLong
          required.count(_.packageId == pkg.latest.packageId) shouldBe 1
        }
      }
    }
  }
}

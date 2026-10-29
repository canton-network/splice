// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data.{ExercisedEvent, Identifier}
import com.digitalasset.canton.BaseTest
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.SvRewardCoupon
import org.lfdecentralizedtrust.splice.store.events.SvRewardCoupon_ArchiveAsBeneficiary
import org.scalatest.wordspec.AnyWordSpec

import java.util.Optional
import scala.jdk.CollectionConverters.*

class ExerciseNodeTest extends AnyWordSpec with BaseTest {

  private val companion = SvRewardCoupon_ArchiveAsBeneficiary

  private def exercise(
      templateId: Identifier = SvRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
      packageName: String = SvRewardCoupon.PACKAGE_NAME,
      choice: String = companion.choiceName,
  ) = new ExercisedEvent(
    Seq.empty.asJava,
    0,
    1,
    templateId,
    packageName,
    Optional.empty(),
    "cid",
    choice,
    companion.argToValue(new amulet.SvRewardCoupon_ArchiveAsBeneficiary()),
    Seq.empty.asJava,
    true,
    1,
    companion.resToValue(
      amulet.SvRewardCoupon_ArchiveAsBeneficiaryResult.SVREWARDCOUPON_ARCHIVEASBENEFICIARYRESULT
    ),
    Seq.empty.asJava,
    false,
  )

  "ExerciseNodeCompanion.unapply" should {

    "match the template, package name and choice" in {
      companion.unapply(exercise()) should not be empty
    }

    "not match a template with the same module and entity name in a different package" in {
      val otherId = SvRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID
      val lookalike = new Identifier("0" * 64, otherId.getModuleName, otherId.getEntityName)
      companion.unapply(
        exercise(templateId = lookalike, packageName = "some-other-package")
      ) shouldBe empty
    }

    "not match a different choice" in {
      companion.unapply(exercise(choice = "SvRewardCoupon_SomeOtherChoice")) shouldBe empty
    }
  }
}

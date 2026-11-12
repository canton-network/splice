// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.BaseTest
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Using

class UploadablePackageTest extends AnyWordSpec with BaseTest {

  private val wallet = DarResources.wallet.latest
  private val amulet = DarResources.amulet.latest

  private def readBytes(pkg: UploadablePackage): ByteString =
    Using.resource(pkg.inputStream())(ByteString.readFrom)

  private val walletBytes = readBytes(UploadablePackage.fromResource(wallet))

  "darSizeBytes" should {
    "be the number of bytes of a classpath DAR" in {
      UploadablePackage.fromResource(wallet).darSizeBytes shouldBe walletBytes.size.toLong
    }
  }

  "distinct" should {
    "drop a DAR listed twice" in {
      val dars = Seq(UploadablePackage.fromResource(wallet), UploadablePackage.fromResource(wallet))
      UploadablePackage.distinct(dars) should have size 1
    }

    "drop the same bytes under another name" in {
      val dars = Seq(
        UploadablePackage.fromByteString("a.dar", walletBytes),
        UploadablePackage.fromByteString("b.dar", walletBytes),
      )
      UploadablePackage.distinct(dars).map(_.resourcePath) shouldBe Seq("a.dar")
    }

    "keep DARs with the same main package but a different size" in {
      val longer = walletBytes.concat(ByteString.copyFrom(Array[Byte](0)))
      val dars = Seq(
        UploadablePackage.fromByteString("a.dar", walletBytes),
        UploadablePackage.fromByteString("b.dar", longer),
      )
      dars.map(_.packageId).distinct shouldBe Seq(wallet.packageId)
      UploadablePackage.distinct(dars) should have size 2
    }

    "keep different packages" in {
      val dars = Seq(UploadablePackage.fromResource(wallet), UploadablePackage.fromResource(amulet))
      UploadablePackage.distinct(dars).map(_.packageId) shouldBe Seq(
        wallet.packageId,
        amulet.packageId,
      )
    }
  }
}

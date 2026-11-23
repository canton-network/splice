package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpec

class TextLimitsTest extends AnyWordSpec with BaseTest {

  "TextLimits" should {

    "match the limits of Splice.TextLimits in Daml" in {
      // Spelled out to notice accidental changes; the Daml tests spell out the same values.
      TextLimits.maxIdentifierLength shouldBe 255
      TextLimits.maxTrackingIdLength shouldBe 279
      TextLimits.maxFreeFormTextLength shouldBe 4096
      TextLimits.maxSvOnboardingTokenLength shouldBe 1900
      TextLimits.maxValidatorOnboardingSecretLength shouldBe 1024
    }

    "count characters like Daml's DA.Text.length" in {
      // U+1F600 is one code point, but two UTF-16 code units.
      val emoji = "😀"
      TextLimits.damlTextLength(emoji * 3) shouldBe 3
      TextLimits.checkMaxLength("value", 3, emoji * 3) shouldBe Right(())
      TextLimits.checkMaxLength("value", 3, "abcd") shouldBe Left(
        "value must be at most 3 characters long, but has length 4"
      )
    }
  }
}

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.CantonConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AsyncWordSpec

class SpliceConfigTest extends AsyncWordSpec with BaseTest {
  private implicit val elc: com.digitalasset.canton.logging.ErrorLoggingContext = SpliceConfig.elc
  val config = ConfigFactory.parseFile(
    new java.io.File("apps/app/src/test/resources/simple-topology-1sv.conf")
  )

  "Validator config is rejected when topup interval < pollingInterval" in {
    SpliceConfig.loadAndValidate(config) shouldBe a[Right[?, ?]]
    val overwrite = ConfigFactory.parseString(
      """
      |canton.validator-apps.aliceValidator.domains.global.buy-extra-traffic.target-throughput = 500000
      |canton.validator-apps.aliceValidator.domains.global.buy-extra-traffic.min-topup-interval = 1s
     """.stripMargin
    )
    val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
    SpliceConfig.loadAndValidate(buggyConfig).left.value.toString should include(
      "topup interval 1 second must not be smaller than the polling interval 30 seconds"
    )
  }
  "disableSvValidatorBftSequencerConnection" should {
    "be rejected if svValidator is not true" in {
      val overwrite = ConfigFactory.parseString(
        """
      |canton.validator-apps.aliceValidator.disable-sv-validator-bft-sequencer-connection = true
     """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(buggyConfig).left.value.toString should include(
        "disableSvValidatorBftSequencerConnection must not be set for non-sv validators"
      )
    }
    "be rejected if sequencer url is not set" in {
      val overwrite = ConfigFactory.parseString(
        """
      |canton.validator-apps.sv1Validator.disable-sv-validator-bft-sequencer-connection = true
     """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(buggyConfig).left.value.toString should include(
        "disableSvValidatorBftSequencerConnection must be set together with domains.global.url"
      )
    }
    "be rejected if set to false and url is set" in {
      val overwrite = ConfigFactory.parseString(
        """
      |canton.validator-apps.sv1Validator.domains.global.url = "http://example.com"
     """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(buggyConfig).left.value.toString should include(
        "domains.global.url must not be set for an SV unless disableSvValidatorBftSequencerConnection is also set"
      )
    }
    "be accepted if set to false for non-sv validator and url is set" in {
      val overwrite = ConfigFactory.parseString(
        """
      |canton.validator-apps.aliceValidator.domains.global.url = "http://example.com"
     """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(buggyConfig) shouldBe a[Right[?, ?]]
    }
    "be accepted if set to true for sv validator and url is set" in {
      val overwrite = ConfigFactory.parseString(
        """
      |canton.validator-apps.sv1Validator.disable-sv-validator-bft-sequencer-connection = true
      |canton.validator-apps.sv1Validator.domains.global.url = "http://example.com"
     """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(buggyConfig) shouldBe a[Right[?, ?]]
    }
  }

  "rewardSharingByParty" should {

    def mkSharingConfig(beneficiaries: String): String =
      s"""
        |canton.validator-apps.aliceValidator.reward-sharing-by-party = {
        |  "alice::1220abc" = {
        |    beneficiaries = [$beneficiaries]
        |    min-ttl-after-sharing = 30h
        |  }
        |}
        """.stripMargin

    def mkBeneficiary(name: String, percentage: String): String =
      s"""{ beneficiary = "$name::1220", percentage = $percentage }"""

    Seq(
      ("two beneficiaries", "0.3, 0.2"),
      ("single beneficiary", "0.5"),
      ("small percentage", "0.01"),
      ("percentage exactly 1.0", "1.0"),
      ("near-total split", "0.5, 0.49"),
      ("exact total split", "0.6, 0.4"),
      ("three-way even split", "0.33, 0.33, 0.33"),
      ("high precision", "0.123456789, 0.876543210"),
      ("ten decimal places", "0.0000000001"),
      ("empty beneficiaries", ""),
    ).foreach { case (desc, percentages) =>
      val beneficiaries = percentages
        .split(",")
        .map(_.trim)
        .filter(_.nonEmpty)
        .zipWithIndex
        .map { case (pct, i) => mkBeneficiary(s"party$i", pct) }
        .mkString(", ")

      s"accept $desc ($percentages)" in {
        val overwrite = ConfigFactory.parseString(mkSharingConfig(beneficiaries))
        val validConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
        SpliceConfig.loadAndValidate(validConfig) shouldBe a[Right[?, ?]]
      }
    }

    Seq(
      ("percentage > 1.0", "1.5", "must be in (0.0, 1.0]"),
      ("percentage = 0", "0.0", "must be in (0.0, 1.0]"),
      ("negative percentage", "-0.1", "must be in (0.0, 1.0]"),
    ).foreach { case (desc, percentage, expectedError) =>
      s"reject $desc" in {
        val overwrite =
          ConfigFactory.parseString(mkSharingConfig(mkBeneficiary("charlie", percentage)))
        val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
        SpliceConfig.loadAndValidate(buggyConfig).left.value.toString should include(expectedError)
      }
    }

    Seq(
      ("sum > 1.0", "0.6, 0.5"),
    ).foreach { case (desc, percentages) =>
      val beneficiaries = percentages
        .split(",")
        .map(_.trim)
        .zipWithIndex
        .map { case (pct, i) => mkBeneficiary(s"party$i", pct) }
        .mkString(", ")

      s"reject percentages with $desc" in {
        val overwrite = ConfigFactory.parseString(mkSharingConfig(beneficiaries))
        val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
        SpliceConfig.loadAndValidate(buggyConfig).left.value.toString should include(
          "must sum to at most 1.0"
        )
      }
    }
  }
}

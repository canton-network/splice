package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.CantonConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AsyncWordSpec

import org.lfdecentralizedtrust.splice.wallet.config.{
  AppRewardBeneficiaryConfig,
  RewardSharingConfig,
}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId

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

  // Shared helper for RewardSharingConfig tests
  private def mkSharingCfg(percentages: BigDecimal*): RewardSharingConfig.BuiltIn =
    RewardSharingConfig.BuiltIn(
      minTtlAfterSharing = NonNegativeFiniteDuration.ofHours(30),
      beneficiaries = percentages.zipWithIndex.map { case (pct, i) =>
        AppRewardBeneficiaryConfig(
          PartyId.tryFromProtoPrimitive(s"party$i::1220"),
          pct,
        )
      },
    )

  private val provider = PartyId.tryFromProtoPrimitive("provider::1220")

  "RewardSharingConfig.providerRemainder" should {
    Seq(
      ("no beneficiaries", Seq.empty[BigDecimal], BigDecimal(1.0)),
      ("single beneficiary", Seq(BigDecimal(0.3)), BigDecimal(0.7)),
      ("two beneficiaries", Seq(BigDecimal(0.3), BigDecimal(0.2)), BigDecimal(0.5)),
      ("full allocation", Seq(BigDecimal(1.0)), BigDecimal(0.0)),
      ("near-total", Seq(BigDecimal(0.5), BigDecimal(0.49)), BigDecimal(0.01)),
    ).foreach { case (desc, percentages, expected) =>
      s"return $expected for $desc" in {
        mkSharingCfg(percentages*).providerRemainder shouldBe expected
      }
    }
  }

  "RewardSharingConfig.allBeneficiaries" should {
    "include provider with remainder" in {
      val all = mkSharingCfg(BigDecimal(0.3), BigDecimal(0.2)).allBeneficiaries(provider)
      all should have size 3
      all.last.beneficiary shouldBe provider
      all.last.percentage shouldBe BigDecimal(0.5)
    }

    "exclude provider when fully allocated" in {
      val all = mkSharingCfg(BigDecimal(1.0)).allBeneficiaries(provider)
      all should have size 1
      all.headOption.value.beneficiary shouldBe PartyId.tryFromProtoPrimitive("party0::1220")
    }

    "return only provider when no beneficiaries" in {
      val all = mkSharingCfg().allBeneficiaries(provider)
      all should have size 1
      all.headOption.value.beneficiary shouldBe provider
      all.headOption.value.percentage shouldBe BigDecimal(1.0)
    }
  }

  "RewardSharingConfig.allDamlBeneficiaries" should {
    "convert percentages to Daml Decimal scale 10" in {
      val all = mkSharingCfg(BigDecimal(0.3), BigDecimal(0.2)).allDamlBeneficiaries(provider)
      all should have size 3
      all.map(_._2.scale()) shouldBe Seq(10, 10, 10)
    }

    Seq(
      ("two-way split", Seq(BigDecimal(0.3), BigDecimal(0.2))),
      ("three-way split", Seq(BigDecimal(0.33), BigDecimal(0.33), BigDecimal(0.33))),
      ("high precision", Seq(BigDecimal(0.123456789), BigDecimal(0.876543210))),
      ("single beneficiary", Seq(BigDecimal(0.5))),
      ("full allocation", Seq(BigDecimal(1.0))),
      ("no beneficiaries", Seq.empty[BigDecimal]),
    ).foreach { case (desc, percentages) =>
      s"$desc sums to exactly 1.0 at Daml precision" in {
        val all = mkSharingCfg(percentages*).allDamlBeneficiaries(provider)
        val sum = all.map(_._2).foldLeft(java.math.BigDecimal.ZERO)(_.add(_))
        sum.compareTo(java.math.BigDecimal.ONE) shouldBe 0
      }
    }
  }

  "rewardSharingConfigByParty" should {

    def mkHoconConfig(beneficiaries: String): String =
      s"""
        |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
        |  "alice::1220abc" = {
        |    beneficiaries = [$beneficiaries]
        |    min-ttl-after-sharing = 30h
        |  }
        |}
        """.stripMargin

    def mkBeneficiary(name: String, percentage: String): String =
      s"""{ beneficiary = "$name::1220", percentage = $percentage }"""

    def beneficiariesFromPcts(percentages: String): String =
      percentages
        .split(",")
        .map(_.trim)
        .filter(_.nonEmpty)
        .zipWithIndex
        .map { case (pct, i) => mkBeneficiary(s"party$i", pct) }
        .mkString(", ")

    Seq(
      ("two beneficiaries", "0.3, 0.2"),
      ("single beneficiary", "0.5"),
      ("small percentage", "0.01"),
      ("percentage exactly 1.0", "1.0"),
      ("exact total split", "0.6, 0.4"),
      ("three-way even split", "0.33, 0.33, 0.33"),
      ("high precision", "0.123456789, 0.876543210"),
      ("empty beneficiaries", ""),
    ).foreach { case (desc, percentages) =>
      s"accept $desc ($percentages)" in {
        val overwrite =
          ConfigFactory.parseString(mkHoconConfig(beneficiariesFromPcts(percentages)))
        val validConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
        SpliceConfig.loadAndValidate(validConfig) shouldBe a[Right[?, ?]]
      }
    }

    Seq(
      ("percentage > 1.0", "1.5", "must be in (0.0, 1.0]"),
      ("percentage = 0", "0.0", "must be in (0.0, 1.0]"),
      ("negative percentage", "-0.1", "must be in (0.0, 1.0]"),
      ("sum > 1.0", "0.6, 0.5", "must sum to at most 1.0"),
    ).foreach { case (desc, percentage, expectedError) =>
      s"reject $desc" in {
        val beneficiaries =
          if (percentage.contains(",")) beneficiariesFromPcts(percentage)
          else mkBeneficiary("charlie", percentage)
        val overwrite = ConfigFactory.parseString(mkHoconConfig(beneficiaries))
        val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
        SpliceConfig.loadAndValidate(buggyConfig).left.value.toString should include(expectedError)
      }
    }

    "accept custom batchSize" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    beneficiaries = [{ beneficiary = "bob::1220", percentage = 0.4 }]
          |    min-ttl-after-sharing = 30h
          |    batch-size = 50
          |  }
          |}
          """.stripMargin
      )
      val validConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(validConfig) shouldBe a[Right[?, ?]]
    }

    "reject batchSize = 0" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    beneficiaries = [{ beneficiary = "bob::1220", percentage = 0.4 }]
          |    min-ttl-after-sharing = 30h
          |    batch-size = 0
          |  }
          |}
          """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig
        .loadAndValidate(buggyConfig)
        .left
        .value
        .toString should include("batchSize")
    }

    def sharingConfigOf(cfg: SpliceConfig): RewardSharingConfig =
      cfg.validatorApps.values
        .flatMap(_.rewardSharingConfigByParty.get("alice::1220abc"))
        .loneElement

    "default to built-in sharing-automation when omitted" in {
      val overwrite = ConfigFactory.parseString(mkHoconConfig(mkBeneficiary("bob", "0.4")))
      val validConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      val loaded = SpliceConfig.loadAndValidate(validConfig).value
      sharingConfigOf(loaded) shouldBe a[RewardSharingConfig.BuiltIn]
    }

    "accept sharing-automation = external with no beneficiaries" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    sharing-automation = "external"
          |  }
          |}
          """.stripMargin
      )
      val validConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      val loaded = SpliceConfig.loadAndValidate(validConfig).value
      sharingConfigOf(loaded) shouldBe RewardSharingConfig.External
    }

    "accept explicit sharing-automation = built-in with beneficiaries" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    sharing-automation = "built-in"
          |    beneficiaries = [{ beneficiary = "bob::1220", percentage = 0.4 }]
          |    min-ttl-after-sharing = 30h
          |  }
          |}
          """.stripMargin
      )
      val validConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      val loaded = SpliceConfig.loadAndValidate(validConfig).value
      sharingConfigOf(loaded) shouldBe a[RewardSharingConfig.BuiltIn]
    }

    "reject sharing-automation = external with beneficiaries" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    sharing-automation = "external"
          |    beneficiaries = [{ beneficiary = "bob::1220", percentage = 0.4 }]
          |  }
          |}
          """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig
        .loadAndValidate(buggyConfig)
        .left
        .value
        .toString should include("beneficiaries must not be set")
    }

    "reject an invalid sharing-automation value" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    sharing-automation = "bogus"
          |  }
          |}
          """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig
        .loadAndValidate(buggyConfig)
        .left
        .value
        .toString should include("SharingAutomation")
    }
  }
}

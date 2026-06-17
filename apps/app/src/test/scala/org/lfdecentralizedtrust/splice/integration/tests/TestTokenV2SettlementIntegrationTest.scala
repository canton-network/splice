package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.tokens.testtokenv2.holding.Token as TestTokenV2
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.plugins.TokenStandardCliSanityCheckPlugin
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.config.ExpectedValidatorOnboardingConfig
import org.lfdecentralizedtrust.splice.util.{
  StandaloneCanton,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}

import java.nio.file.Paths

// Not checking Daml compatibility because it doesn't make sense with TestTokenV2
@org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck
class TestTokenV2SettlementIntegrationTest
    extends IntegrationTest
    with TokenStandardTest
    with WalletTestUtil
    with HasActorSystem
    with TimeTestUtil
    with HasExecutionContext
    with TriggerTestUtil
    with StandaloneCanton
    with TokenStandardV2TestUtil {

  override def dbsSuffix: String = "test_token_v2_settlement"
  val dbName = s"participant_alice_validator_${dbsSuffix}"
  override def usesDbs = Seq(dbName) ++ super.usesDbs

  // Can sometimes be unhappy when doing funky `withCanton` things; disabling them for simplicity
  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override protected lazy val tokenStandardCliBehavior
      : TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior =
    TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior.IgnoreForTemplateIds(
      Seq(TestTokenV2.TEMPLATE_ID)
    )

  private val testTokenV2DarPath = Paths
    .get(
      "token-standard/examples/splice-test-token-v2/.daml/dist/splice-test-token-v2-current.dar"
    )
    .toAbsolutePath
    .toString

  override def environmentDefinition: SpliceEnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1SvWithLocalValidator(this.getClass.getSimpleName)
      .withoutAliceValidatorConnectingToSplitwell
      .withSequencerConnectionsFromScanDisabled()
      .addConfigTransform((_, conf) =>
        conf.copy(
          svApps = conf.svApps.map { case (instanceName, svApp) =>
            instanceName -> svApp.copy(expectedValidatorOnboardings =
              svApp.expectedValidatorOnboardings :+ ExpectedValidatorOnboardingConfig(
                "aliceExtraValidator"
              )
            )
          },
          validatorApps =
            conf.validatorApps.updatedWith(InstanceName.tryCreate("aliceValidatorLocal")) {
              _.map { aliceValidatorConfig =>
                aliceValidatorConfig.copy(
                  adminApi = aliceValidatorConfig.adminApi
                    .copy(internalPort = Some(aliceValidatorConfig.adminApi.port + 22_000)),
                  onboarding =
                    aliceValidatorConfig.onboarding.map(_.copy(secret = "aliceExtraValidator")),
                )
              }
            },
        )
      )
  }

  "TestTokenV2 should be settleable" in { implicit env =>
    initDso()
    withCanton(
      Seq(
        testResourcesPath / "standalone-participant-extra.conf"
      ),
      Seq(),
      "test_token_v2_settlement",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> dbName,
    ) {
      Seq(
        sv1ValidatorBackend,
        aliceValidatorBackend,
        bobValidatorBackend,
        aliceValidatorLocalBackend,
      ).foreach { validatorBackend =>
        validatorBackend.startSync()
        validatorBackend.participantClient
          .upload_dar_unless_exists(testTokenV2DarPath)
      }
    }
  }

}

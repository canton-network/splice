package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.transferpreapproval.TransferPreapprovalProposal
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import scala.jdk.CollectionConverters.*

class SvOnboardingVettingIntegrationTest
    extends SvIntegrationTestBase
    with WalletTestUtil
    with SvTestUtil {

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    super.environmentDefinition.withNoVettedPackages(implicit env =>
      env.validators.local.map(_.participantClient)
    )

  "SV can onboard when ACS includes splice-wallet contracts" in { implicit env =>
    startAllSync(sv1Backend, sv1ValidatorBackend, sv1ScanBackend)
    val sv1Party = sv1Backend.getDsoInfo().svParty
    clue("Create a contract from splice-wallet with DSO as an observer") {
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(sv1Party),
        commands = new TransferPreapprovalProposal(
          sv1Party.toProtoPrimitive,
          dsoParty.toProtoPrimitive, // This is the important part: The provider is an observer so this is in the DSO party acs.
          java.util.Optional.of(dsoParty.toProtoPrimitive),
        ).create.commands.asScala.toSeq,
      )
    }
    clue("SV2 tries to onboard") {
      sv2Backend.startSync()
    }
  }
}

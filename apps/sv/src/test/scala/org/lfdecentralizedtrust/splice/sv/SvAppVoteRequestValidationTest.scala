package org.lfdecentralizedtrust.splice.sv

import com.digitalasset.canton.BaseTest
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.RegisteredSynchronizer
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_ArchiveSynchronizerRegistration,
  DsoRules_RegisterSynchronizer,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_ArchiveSynchronizerRegistration,
  SRARC_RegisterSynchronizer,
}
import org.scalatest.wordspec.AnyWordSpec

class SvAppVoteRequestValidationTest extends AnyWordSpec with BaseTest {

  private val fingerprint = "12207f9750b391b3bb521ead855db783d8cf661f17108e563ca72c0084052f035011"

  private def register(synchronizerId: String) =
    new ARC_DsoRules(
      new SRARC_RegisterSynchronizer(
        new DsoRules_RegisterSynchronizer(
          synchronizerId,
          s"paymaster::$fingerprint",
          java.math.BigDecimal.ONE.setScale(10),
          java.math.BigDecimal.ONE.setScale(10),
        )
      )
    )

  "validateVoteRequestAction" should {
    "accept a registration with a well-formed synchronizer id" in {
      SvApp.validateVoteRequestAction(register(s"dedicated::$fingerprint")) shouldBe Right(())
    }

    "reject a registration whose synchronizer id Canton cannot parse" in {
      forAll(
        Seq(
          "",
          "dedicated",
          "dedicated::",
          s"::$fingerprint",
          s"dedicated::$fingerprint::extra",
          s"dedicated!::$fingerprint",
          s"${"a" * 186}::$fingerprint",
          s"dedicated::${"1" * 69}",
        )
      ) { synchronizerId =>
        SvApp.validateVoteRequestAction(register(synchronizerId)).left.value should startWith(
          "Invalid synchronizer id"
        )
      }
    }

    "accept other actions" in {
      SvApp.validateVoteRequestAction(
        new ARC_DsoRules(
          new SRARC_ArchiveSynchronizerRegistration(
            new DsoRules_ArchiveSynchronizerRegistration(
              new RegisteredSynchronizer.ContractId("00" + "ab" * 32)
            )
          )
        )
      ) shouldBe Right(())
    }
  }
}

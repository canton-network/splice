// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.Metadata
import org.lfdecentralizedtrust.splice.http.v0.definitions.AllocationInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpiredAmuletAllocationTrigger
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, TriggerTestUtil, WalletTestUtil}

import java.time.Duration
import java.util.Optional

// Standalone: needs runTokenStandardCliSanityCheck off to allow an unhosted receiver.
class ExpiredAmuletAllocationTriggerIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil {

  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition.simpleTopology1SvWithSimTime(this.getClass.getSimpleName)

  "ExpiredAmuletAllocationTrigger" should {

    "expire a V1 allocation whose transfer-leg receiver is not a hosted party" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(1000)

      // Well-formed but unhosted; the receiver is free-form data, not a stakeholder.
      val unhostedReceiver = PartyId.tryFromProtoPrimitive(
        "nonexistent-receiver::1220b3eeb21b02e14945e419c5d9e986ce8102171c50e1444010ab054e11eba262c9"
      )

      val now = getLedgerTime
      val specification = new allocationv1.AllocationSpecification(
        new allocationv1.SettlementInfo(
          aliceValidatorBackend.getValidatorPartyId().toProtoPrimitive,
          new allocationv1.Reference("test-ref", Optional.empty),
          now.minusSeconds(60).toInstant, // requestedAt in the past
          now.plusSeconds(300).toInstant, // allocateBefore
          now.plusSeconds(600).toInstant, // settleBefore == expiresAt
          new Metadata(java.util.Map.of()),
        ),
        "leg1",
        new allocationv1.TransferLeg(
          aliceParty.toProtoPrimitive,
          unhostedReceiver.toProtoPrimitive,
          BigDecimal(50).bigDecimal.setScale(10),
          new holdingv1.InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
          new Metadata(java.util.Map.of()),
        ),
      )

      clue("Create the allocation") {
        inside(aliceWalletClient.allocateAmulet(specification).output) {
          case members.AllocationInstructionResultCompleted(_) => succeed
        }
      }

      clue("Allocation is visible before expiry") {
        eventually() {
          aliceWalletClient.listAmuletAllocations() should have size 1
        }
      }

      advanceTime(Duration.ofSeconds(720)) // past expiry

      // Trigger is paused by default; resume it and require the allocation to be archived.
      setTriggersWithin(triggersToResumeAtStart =
        Seq(sv1Backend.dsoDelegateBasedAutomation.trigger[ExpiredAmuletAllocationTrigger])
      ) {
        eventually() {
          aliceWalletClient.listAmuletAllocations() shouldBe empty
        }
      }
    }
  }
}

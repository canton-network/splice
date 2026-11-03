// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv.offboarding

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.topology.{Namespace, ParticipantId, UniqueIdentifier}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.offboarding.SvOffboardingPartyToParticipantProposalTrigger.{
  OffboardingResult,
  participantsToOffboard,
}
import org.scalatest.wordspec.AnyWordSpec

class SvOffboardingPartyToParticipantProposalTriggerTest extends AnyWordSpec with BaseTest {

  private def participant(name: String) =
    ParticipantId(
      UniqueIdentifier.tryCreate(name, Namespace(Fingerprint.tryFromString(s"${name}ns")))
    )

  private val sv1 = participant("sv1")
  private val sv2 = participant("sv2")
  private val sv3 = participant("sv3")

  "participantsToOffboard" should {

    "offboard a hosting participant of an offboarded SV" in {
      participantsToOffboard(
        offboardedParticipants = Seq(sv3.toProtoPrimitive),
        activeSvParticipants = Seq(sv1.toProtoPrimitive, sv2.toProtoPrimitive),
        currentlyHostingParticipants = Seq(sv1, sv2, sv3),
      ) shouldBe OffboardingResult(toOffboard = Seq(sv3), stillUsedByActiveSv = Seq.empty)
    }

    "not offboard a participant of an offboarded SV that no longer hosts the DSO party" in {
      participantsToOffboard(
        offboardedParticipants = Seq(sv3.toProtoPrimitive),
        activeSvParticipants = Seq(sv1.toProtoPrimitive, sv2.toProtoPrimitive),
        currentlyHostingParticipants = Seq(sv1, sv2),
      ) shouldBe OffboardingResult(toOffboard = Seq.empty, stillUsedByActiveSv = Seq.empty)
    }

    "not offboard a participant that is still the participant of an active SV" in {
      participantsToOffboard(
        offboardedParticipants = Seq(sv2.toProtoPrimitive, sv3.toProtoPrimitive),
        activeSvParticipants = Seq(sv1.toProtoPrimitive, sv2.toProtoPrimitive),
        currentlyHostingParticipants = Seq(sv1, sv2, sv3),
      ) shouldBe OffboardingResult(toOffboard = Seq(sv3), stillUsedByActiveSv = Seq(sv2))
    }
  }
}

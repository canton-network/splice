// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.topology.{Namespace, ParticipantId, PartyId, UniqueIdentifier}
import org.lfdecentralizedtrust.splice.codegen.java.splice.svonboarding.SvOnboardingConfirmed
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding.SvOnboardingPartyToParticipantProposalTrigger.{
  HostingProposal,
  participantsToAuthorize,
}
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class SvOnboardingPartyToParticipantProposalTriggerTest extends AnyWordSpec with BaseTest {

  private def namespace(name: String) = Namespace(Fingerprint.tryFromString(name))
  private def participant(name: String, ns: Namespace) =
    ParticipantId(UniqueIdentifier.tryCreate(name, ns))
  private def party(name: String, ns: Namespace) = PartyId(UniqueIdentifier.tryCreate(name, ns))

  private val dso = party("DSO", namespace("dso"))

  private val sv1Namespace = namespace("sv1ns")
  private val sv1Participant = participant("sv1", sv1Namespace)
  private val sponsorNamespace = namespace("sponsorns")
  private val sponsorParticipant = participant("sponsor", sponsorNamespace)
  private val candidateNamespace = namespace("candidatens")
  private val candidateParty = party("candidate", candidateNamespace)
  private val candidateParticipant = participant("candidate", candidateNamespace)

  private val currentlyHosting = Seq(sv1Participant, sponsorParticipant)

  private def confirmed(svParty: PartyId, svParticipantId: ParticipantId) =
    new SvOnboardingConfirmed(
      svParty.toProtoPrimitive,
      "candidate",
      10000L,
      svParticipantId.toProtoPrimitive,
      "reason",
      dso.toProtoPrimitive,
      Instant.EPOCH,
    )

  private def proposalAdding(newParticipant: ParticipantId) =
    HostingProposal(
      Seq(candidateNamespace.fingerprint, sponsorNamespace.fingerprint),
      currentlyHosting :+ newParticipant,
    )

  "participantsToAuthorize" should {

    "authorize the confirmed participant of the candidate" in {
      participantsToAuthorize(
        sv1Namespace,
        Seq(confirmed(candidateParty, candidateParticipant)),
        currentlyHosting,
        Seq(proposalAdding(candidateParticipant)),
      ) shouldBe Seq(candidateParticipant)
    }

    "not authorize a participant that is not signed by both candidate and sponsor" in {
      participantsToAuthorize(
        sv1Namespace,
        Seq(confirmed(candidateParty, candidateParticipant)),
        currentlyHosting,
        Seq(
          HostingProposal(
            Seq(candidateNamespace.fingerprint),
            currentlyHosting :+ candidateParticipant,
          )
        ),
      ) shouldBe empty
    }

    "not authorize a participant of the candidate's namespace that is not the confirmed one" in {
      val otherCandidateParticipant = participant("candidate-extra", candidateNamespace)
      participantsToAuthorize(
        sv1Namespace,
        Seq(confirmed(candidateParty, candidateParticipant)),
        currentlyHosting,
        Seq(proposalAdding(otherCandidateParticipant)),
      ) shouldBe empty
    }

    "not authorize a participant whose namespace does not match the confirmed party's namespace" in {
      val foreignParticipant = participant("foreign", namespace("foreignns"))
      participantsToAuthorize(
        sv1Namespace,
        Seq(confirmed(candidateParty, foreignParticipant)),
        currentlyHosting,
        Seq(proposalAdding(foreignParticipant)),
      ) shouldBe empty
    }

    "not authorize a participant if a participant of the same namespace already hosts the DSO party" in {
      val secondSponsorParticipant = participant("sponsor-extra", sponsorNamespace)
      val sponsorParty = party("sponsor", sponsorNamespace)
      participantsToAuthorize(
        sv1Namespace,
        Seq(confirmed(sponsorParty, secondSponsorParticipant)),
        currentlyHosting,
        Seq(
          HostingProposal(
            Seq(sponsorNamespace.fingerprint, candidateNamespace.fingerprint),
            currentlyHosting :+ secondSponsorParticipant,
          )
        ),
      ) shouldBe empty
    }
  }
}

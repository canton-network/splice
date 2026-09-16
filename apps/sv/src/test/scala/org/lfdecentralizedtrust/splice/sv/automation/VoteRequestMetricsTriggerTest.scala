// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{Reason, Vote}
import org.lfdecentralizedtrust.splice.store.StoreTestBase
import org.lfdecentralizedtrust.splice.sv.automation.VoteRequestMetricsTrigger.VoteRequestCounts

import java.util.Optional

class VoteRequestMetricsTriggerTest extends StoreTestBase {

  "countByState" should {
    "partition vote requests by their state relative to the SV" in {
      val sv = userParty(1)
      val otherSv = userParty(2)
      def vote(svParty: com.digitalasset.canton.topology.PartyId): Vote =
        new Vote(svParty.toProtoPrimitive, true, new Reason("", ""), Optional.empty())

      val notVoted = voteRequest(requester = otherSv, votes = Seq(vote(otherSv)))
      val voted = voteRequest(requester = otherSv, votes = Seq(vote(otherSv), vote(sv)))
      val ownRequest = voteRequest(requester = sv, votes = Seq(vote(sv)))
      // ready to close counts as such regardless of whether the SV has voted
      val readyVoted = voteRequest(requester = sv, votes = Seq(vote(sv)))
      val readyNotVoted = voteRequest(requester = otherSv, votes = Seq(vote(otherSv)))

      VoteRequestMetricsTrigger.countByState(
        Seq(notVoted, voted, ownRequest, readyVoted, readyNotVoted),
        Set(readyVoted.contractId, readyNotVoted.contractId),
        sv.toProtoPrimitive,
      ) shouldBe VoteRequestCounts(actionNeeded = 1, inProgress = 2, readyToClose = 2)
    }
  }
}

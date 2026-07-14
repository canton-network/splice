// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.sv.util.ContractStakeholders.*
import org.lfdecentralizedtrust.splice.util.AssignedContract

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait ContractStakeholders[T] {

  /** Returns (nonDsoStakeholders, allStakeholders) across a batch of contracts. */
  def computeBatchStakeholders[TCid](
      contracts: Seq[AssignedContract[TCid, T]],
      dso: PartyId,
  ): (Set[PartyId], Set[PartyId]) = {
    val nonDso = contracts.flatMap(c => nonDsoStakeholders(c.payload)).toSet
    (nonDso, nonDso + dso)
  }

  def nonDsoStakeholders(payload: Any): Seq[PartyId] =
    payload match {
      case a: splice.amulet.Amulet =>
        amuletStakeholders(a, includeDsoParty = false)
      case la: splice.amulet.LockedAmulet =>
        lockedAmuletStakeholders(la, includeDsoParty = false)
      case a: splice.amuletallocation.AmuletAllocation =>
        amuletAllocationStakeholders(a, includeDsoParty = false)
      case a: splice.amuletallocationv2.AmuletAllocationV2 =>
        amuletAllocationV2Stakeholders(a, includeDsoParty = false)
      case i: splice.amulettransferinstruction.AmuletTransferInstruction =>
        transferInstructionStakeholders(i, includeDsoParty = false)
      case m: splice.amulet.FeaturedAppActivityMarker =>
        featuredAppActivityMarkerStakeholders(m, includeDsoParty = false)
      case c: splice.amulet.RewardCouponV2 =>
        rewardCouponV2Stakeholders(c, includeDsoParty = false)
      case _ =>
        throw new IllegalArgumentException(s"Unsupported payload type: ${payload.getClass}")
    }
}

object ContractStakeholders {

  private def parsePartyIds(strings: Seq[String]): Seq[PartyId] =
    strings.map(PartyId.tryFromProtoPrimitive)

  def amuletStakeholders(c: splice.amulet.Amulet, includeDsoParty: Boolean = true): Seq[PartyId] =
    parsePartyIds(if (includeDsoParty) Seq(c.dso, c.owner) else Seq(c.owner))

  def lockedAmuletStakeholders(
      c: splice.amulet.LockedAmulet,
      includeDsoParty: Boolean = true,
  ): Seq[PartyId] =
    parsePartyIds(
      (if (includeDsoParty) Seq(c.amulet.dso) else Seq.empty) ++
        Seq(c.amulet.owner) ++ c.lock.holders.asScala
    )

  def amuletAllocationStakeholders(
      c: splice.amuletallocation.AmuletAllocation,
      includeDsoParty: Boolean = true,
  ): Seq[PartyId] =
    parsePartyIds(Seq(c.allocation.transferLeg.sender, c.allocation.settlement.executor))
  // Note: DSO is not a payload field here, so includeDsoParty has no effect

  def amuletAllocationV2Stakeholders(
      c: splice.amuletallocationv2.AmuletAllocationV2,
      includeDsoParty: Boolean = true,
  ): Seq[PartyId] =
    parsePartyIds(
      Seq(c.allocation.admin) ++
        c.allocation.authorizer.owner.toScala.toList ++
        c.settlement.executors.asScala
    )

  def transferInstructionStakeholders(
      c: splice.amulettransferinstruction.AmuletTransferInstruction,
      includeDsoParty: Boolean = true,
  ): Seq[PartyId] =
    parsePartyIds(Seq(c.transfer.sender, c.transfer.receiver))

  def featuredAppActivityMarkerStakeholders(
      c: splice.amulet.FeaturedAppActivityMarker,
      includeDsoParty: Boolean = true,
  ): Seq[PartyId] =
    parsePartyIds(
      (if (includeDsoParty) Seq(c.dso) else Seq.empty) ++
        Seq(c.provider, c.beneficiary)
    )

  def rewardCouponV2Stakeholders(
      c: splice.amulet.RewardCouponV2,
      includeDsoParty: Boolean = true,
  ): Seq[PartyId] =
    parsePartyIds(
      (if (includeDsoParty) Seq(c.dso) else Seq.empty) ++
        (if (c.providerIsObserver) c.provider +: c.beneficiary.toScala.toList else Seq.empty)
    )
}

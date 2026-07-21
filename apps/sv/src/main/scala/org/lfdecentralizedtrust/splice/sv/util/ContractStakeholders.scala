// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.util.Contract

trait ContractStakeholders[T] {

  def informees(payload: T): Seq[String]

  def dso(payload: T): String

  final def getStakeholders(payload: T): Seq[PartyId] =
    getInformees(payload) :+ getDsoParty(payload)

  private final def getInformees(payload: T): Seq[PartyId] =
    informees(payload).map(PartyId.tryFromProtoPrimitive)

  private final def getDsoParty(payload: T): PartyId =
    PartyId.tryFromProtoPrimitive(dso(payload))

  // ExpireRewardCouponTrigger and FeaturedAppActivityMarkerTrigger do not use BatchedMultiDomainExpiredContractTrigger
  final def getInformeesFromContracts[TCid](
      contracts: Seq[Contract[TCid, T]]
  ): Set[PartyId] =
    contracts.flatMap(c => getInformees(c.payload)).toSet

}

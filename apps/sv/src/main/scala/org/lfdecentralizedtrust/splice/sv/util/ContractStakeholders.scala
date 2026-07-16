// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract}

trait ContractStakeholders[T] {

  def observers(payload: T): Seq[String]

  def dso(payload: T): Option[String]

  final def getObservers(payload: T): Seq[PartyId] =
    observers(payload).map(PartyId.tryFromProtoPrimitive)

  final def getDsoParty(payload: T): Seq[PartyId] =
    dso(payload).map(PartyId.tryFromProtoPrimitive).toList

  final def getStakeholders(payload: T): Seq[PartyId] =
    getObservers(payload) ++ getDsoParty(payload)

  final def getObserversFromAssignedContracts[TCid](
      contracts: Seq[AssignedContract[TCid, T]]
  ): Set[PartyId] =
    contracts.flatMap(c => getObservers(c.payload)).toSet

  final def getObserversFromContracts[TCid](contracts: Seq[Contract[TCid, T]]): Set[PartyId] =
    contracts.flatMap(c => getObservers(c.payload)).toSet
}

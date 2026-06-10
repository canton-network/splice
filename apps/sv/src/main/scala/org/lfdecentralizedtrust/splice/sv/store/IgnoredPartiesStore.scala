// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.store

import com.digitalasset.canton.topology.PartyId

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

class IgnoredPartiesStore(initialParties: Set[PartyId]) {

  private val parties: ConcurrentHashMap.KeySetView[PartyId, java.lang.Boolean] = {
    val set = ConcurrentHashMap.newKeySet[PartyId]()
    set.addAll(initialParties.asJava)
    set
  }

  def addAll(newParties: Iterable[PartyId]): Unit =
    newParties.foreach(parties.add)

  def getAll: Set[PartyId] = parties.asScala.toSet
}

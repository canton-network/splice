// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.events

import com.daml.ledger.javaapi.data.CreatedEvent
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsobootstrap.DsoBootstrap
import org.lfdecentralizedtrust.splice.util.Contract

object DsoBootstrapCreate {
  type TCid = DsoBootstrap.ContractId
  type T = DsoBootstrap
  type ContractType = Contract[TCid, T]
  val companion = DsoBootstrap.COMPANION

  def unapply(event: CreatedEvent): Option[ContractType] =
    Contract.fromCreatedEvent(companion)(event)
}

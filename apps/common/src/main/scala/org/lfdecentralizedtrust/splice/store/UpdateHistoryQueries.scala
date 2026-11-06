// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.UpdateHistoryQueries

import com.daml.ledger.javaapi.data.codegen.ContractId
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractCompanion
import org.lfdecentralizedtrust.splice.store.UpdateHistory.SelectFromCreateEvents
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
import org.lfdecentralizedtrust.splice.util.Contract

trait UpdateHistoryQueries extends AcsJdbcTypes {

  protected def contractFromEvent[C, TCId <: ContractId[?], T](companion: C)(
      row: SelectFromCreateEvents
  )(implicit
      companionClass: ContractCompanion[C, TCId, T]
  ): Contract[TCId, T] = {
    companionClass
      .fromCreatedEvent(companion)(row.toCreatedEvent.event)
      .getOrElse(throw new RuntimeException("Failed to decode contract"))
  }
}

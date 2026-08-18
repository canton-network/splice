// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

trait UnavailablePartiesStore {

  // Adds or updates parties
  def addParties(parties: Seq[PartyId], nowMicros: Long)(implicit tc: TraceContext): Future[Unit]

  // Removes specific parties from the store
  def removeParties(parties: Seq[PartyId])(implicit tc: TraceContext): Future[Int]

  // Removes parties from the table with matching store ID
  def removePartiesUpToStoreId(storeId: Long)(implicit tc: TraceContext): Future[Int]

  // Lists parties that are being ignored
  def listParties(nowMicros: Long)(implicit tc: TraceContext): Future[Seq[PartyId]]

}

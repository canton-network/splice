// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

trait UnavailablePartiesStore {

  // Adds or updates parties as of now
  def addParties(parties: Seq[PartyId])(implicit tc: TraceContext): Future[Unit]

  // Removes specific parties from the store
  def removeParties(parties: Seq[PartyId])(implicit tc: TraceContext): Future[Int]

  // Removes parties from the table with matching store ID
  def removePartiesUpToStoreId(storeId: Long)(implicit tc: TraceContext): Future[Int]

  // Lists parties that are being ignored as of now
  def listParties()(implicit tc: TraceContext): Future[Seq[PartyId]]

}

object UnavailablePartiesStore {

  def listParties(
      store: Option[UnavailablePartiesStore]
  )(implicit tc: TraceContext): Future[Set[PartyId]] =
    store.fold(Future.successful(Set.empty[PartyId]))(
      _.listParties().map(_.toSet)(ExecutionContext.parasitic)
    )
}

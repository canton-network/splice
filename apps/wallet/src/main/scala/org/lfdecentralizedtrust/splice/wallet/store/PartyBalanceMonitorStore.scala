// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

/** Persists the last time the balance of a wallet party was observed at or below the allowed limit.
  * An instance is bound to a single wallet store, i.e., a single party.
  */
trait PartyBalanceMonitorStore {

  def getLastBelowLimit()(implicit tc: TraceContext): Future[Option[CantonTimestamp]]

  def setLastBelowLimit(timestamp: CantonTimestamp)(implicit tc: TraceContext): Future[Unit]
}

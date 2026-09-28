// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

trait BackfillUpperBound {
  def endRecordTime(implicit tc: TraceContext): Future[CantonTimestamp]
}

object CatchUpWithPeers extends BackfillUpperBound {
  override def endRecordTime(implicit tc: TraceContext): Future[CantonTimestamp] =
    Future.successful(CantonTimestamp.MaxValue)
}

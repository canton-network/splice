// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.scan.store.historystart.{HistoryStart, ScanHistoryStart}

import scala.concurrent.{ExecutionContext, Future}

sealed trait BackfillEnd

object BackfillEnd {
  final case class CopyUpTo(firstOwnSegmentStart: CantonTimestamp) extends BackfillEnd
  case object HistoryComplete extends BackfillEnd
  case object NotYetKnown extends BackfillEnd
}

trait BackfillUpperBound {
  def end(implicit tc: TraceContext): Future[BackfillEnd]
}

class UpToFirstOwnSegment(historyStart: ScanHistoryStart, storageConfig: ScanStorageConfig)(implicit
    ec: ExecutionContext
) extends BackfillUpperBound {
  override def end(implicit tc: TraceContext): Future[BackfillEnd] =
    historyStart.get.map {
      case None => BackfillEnd.NotYetKnown
      case Some(HistoryStart.Genesis) => BackfillEnd.HistoryComplete
      case Some(start: HistoryStart.From) =>
        BackfillEnd.CopyUpTo(start.firstOwnSegmentStart(storageConfig))
    }
}

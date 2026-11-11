// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.ActivityRecordMetaStore
import org.lfdecentralizedtrust.splice.scan.store.ActivityRecordMetaStore.RoundIngestionStatus

import scala.concurrent.{ExecutionContext, Future}

/** Facade over the two concrete activity-record stores: the TBAR app-side
  * `DbAppActivityRecordStore` (fed from verdicts) and the CIP-0104 extension
  * hook's `DbExtraActivityRecordStore` (fed from
  * `ActivityRecorder_DsoRecordActivity` exercises). Combines their
  * meta-store surfaces into the shape the `ActivityRecordMetaStore` trait
  * exposes to callers (triggers, HTTP handlers, automation service), so
  * the two sources look like a single unified round-completion view.
  *
  * Combine semantics:
  *   - `earliestRoundWithCompleteAppActivity`: max of the two stores'
  *     values — completeness from genesis requires BOTH to have reached
  *     the round. Returns None if either store is None.
  *   - `latestRoundWithCompleteAppActivity`: min of the two — the merged
  *     frontier is bounded by whichever store is slower. Returns None if
  *     either store is None.
  *   - `ingestionStatusForRound`: if either store reports `CannotProvide`,
  *     the facade returns `CannotProvide` (caller should delegate to BFT).
  *     Otherwise `Undetermined` (either or both stores still catching up).
  *   - `startedIngestingAt`: the earlier of the two start times when both
  *     are available; the single non-None value otherwise. Matches the
  *     "earliest moment anything was ingested from either source"
  *     interpretation.
  */
class DbActivityRecordMetaStore(
    appActivityRecordStore: DbAppActivityRecordStore,
    extraActivityRecordStore: DbExtraActivityRecordStore,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends ActivityRecordMetaStore
    with NamedLogging {

  override def startedIngestingAt(implicit tc: TraceContext): Future[Option[Long]] =
    appActivityRecordStore.startedIngestingAt
      .zip(extraActivityRecordStore.startedIngestingAt)
      .map {
        case (Some(a), Some(e)) => Some(math.min(a, e))
        case (someA, None) => someA
        case (None, someE) => someE
      }

  override def earliestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    appActivityRecordStore
      .earliestRoundWithCompleteAppActivity()
      .zip(extraActivityRecordStore.earliestRoundWithCompleteAppActivity())
      .map {
        case (Some(a), Some(e)) => Some(math.max(a, e))
        case _ => None
      }

  override def latestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    appActivityRecordStore
      .latestRoundWithCompleteAppActivity()
      .zip(extraActivityRecordStore.latestRoundWithCompleteAppActivity())
      .map {
        case (Some(a), Some(e)) => Some(math.min(a, e))
        case _ => None
      }

  override def ingestionStatusForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[RoundIngestionStatus] =
    appActivityRecordStore
      .ingestionStatusForRound(roundNumber)
      .zip(extraActivityRecordStore.ingestionStatusForRound(roundNumber))
      .map {
        case (RoundIngestionStatus.CannotProvide, _) => RoundIngestionStatus.CannotProvide
        case (_, RoundIngestionStatus.CannotProvide) => RoundIngestionStatus.CannotProvide
        case _ => RoundIngestionStatus.Undetermined
      }
}

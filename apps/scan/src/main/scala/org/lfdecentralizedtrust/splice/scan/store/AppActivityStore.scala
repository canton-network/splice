// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

/** Store interface for app activity record queries.
  * Decouples callers from the DB implementation.
  */
trait AppActivityStore {

  /** Ingestion status for a specific round, used by the Scan HTTP
    * endpoints when no root hash or activity totals are yet stored.
    */
  def ingestionStatusForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[AppActivityStore.RoundIngestionStatus]

  /** Find the earliest round for which all app activity records have been ingested.
    */
  def earliestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]]

  /** Find the latest round for which all app activity records have been ingested.
    */
  def latestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]]

  /** The record time of the first activity record in the store. */
  def startedIngestingAt(implicit tc: TraceContext): Future[Option[Long]]
}

object AppActivityStore {

  /** Whether this Scan can ever be authoritative for a given round,
    * or whether the answer will arrive as ingestion catches up.
    */
  sealed trait RoundIngestionStatus

  object RoundIngestionStatus {

    /** Cannot compute an answer for this round from local state.
      * Callers should delegate to BFT read.
      */
    case object CannotProvide extends RoundIngestionStatus

    /** Do not yet have an answer but expect to have one after
      * ingesting up to this round. Callers should retry.
      */
    case object Undetermined extends RoundIngestionStatus
  }
}

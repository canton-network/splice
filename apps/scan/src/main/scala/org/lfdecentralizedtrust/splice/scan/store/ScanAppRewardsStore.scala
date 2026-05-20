// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.rewards.RewardComputationInputs
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore.RewardComputationSummary

import scala.concurrent.Future

/** Store interface for the CIP-0104 reward computation pipeline.
  * Decouples RewardComputationTrigger from the DB implementation.
  */
trait ScanAppRewardsStore {

  /** Returns the subset of the given round numbers for which reward
    * computation has already completed (i.e. a root hash exists).
    */
  def roundsWithComputedRewards(rounds: Seq[Long])(implicit
      tc: TraceContext
  ): Future[Set[Long]]

  /** Runs the full reward computation pipeline for a single round:
    * aggregation, CC conversion, and Merkle tree hashing.
    * MUST only be called on rounds for which all app activity records have
    * been ingested and for which the reward information has not yet been computed.
    */
  def computeAndStoreRewards(
      roundNumber: Long,
      batchSize: Int,
      inputs: RewardComputationInputs,
  )(implicit tc: TraceContext): Future[RewardComputationSummary]
}

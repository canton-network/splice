// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.votesbackfilling

import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore.{
  PeerVoteResult,
  VotesBackfillCursor,
}
import org.lfdecentralizedtrust.splice.scan.util.PeerBftScanConnection

import scala.concurrent.{ExecutionContext, Future}

/** Where [[VotesStoreBackfilling]] reads the vote results from: the peer Scans. */
trait PeerVotesSource {

  /** A page of at most `count` vote results strictly before `cursor`, newest first.
    * Empty if there is nothing before the cursor. Fails if the peers cannot serve the page yet,
    * and with [[org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore.UndecodablePeerVoteResult]]
    * if this app cannot decode a vote result that they served.
    */
  def votesBefore(cursor: VotesBackfillCursor, count: Int)(implicit
      tc: TraceContext
  ): Future[Seq[PeerVoteResult]]
}

/** Reads from the peer Scans of this SV, with BFT agreement between them.
  * Decodes the vote results only once the peers agree on them.
  */
class BftPeerVotesSource(peerConnection: PeerBftScanConnection)(implicit ec: ExecutionContext)
    extends PeerVotesSource
    with AutoCloseable {

  override def votesBefore(cursor: VotesBackfillCursor, count: Int)(implicit
      tc: TraceContext
  ): Future[Seq[PeerVoteResult]] =
    peerConnection.connection.flatMap(_.getVotesBefore(cursor, count)).map(_.map(_.decode))

  override def close(): Unit = peerConnection.close()
}

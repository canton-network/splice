// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.transaction.PartyToParticipant

def requireEnv(name: String): String =
  sys.env.getOrElse(name, sys.error(s"Environment variable $name must be set"))

def requireNonEmptyListEnv(name: String): Set[String] = {
  val values = requireEnv(name).split(",").map(_.trim).filter(_.nonEmpty).toSet
  if (values.isEmpty) {
    sys.error(s"Environment variable $name must contain at least one value")
  }
  values
}

val trackedKeys = requireNonEmptyListEnv("TOPOLOGY_KEY_MONITOR_KEYS")
val expectedSynchronizerAliases = requireNonEmptyListEnv(
  "TOPOLOGY_KEY_MONITOR_EXPECTED_SYNCHRONIZER_ALIASES"
)
val pollIntervalSeconds = requireEnv("TOPOLOGY_KEY_MONITOR_POLL_INTERVAL").toLong
val slowQueryThresholdSeconds = requireEnv("TOPOLOGY_KEY_MONITOR_SLOW_QUERY_THRESHOLD").toLong

logger.info(
  s"Starting topology key monitor for keys ${trackedKeys.toSeq.sorted.mkString(", ")} " +
    s"on synchronizers ${expectedSynchronizerAliases.toSeq.sorted.mkString(", ")}, polling every ${pollIntervalSeconds}s"
)

val startedAt = CantonTimestamp.now()
// Per synchronizer and kind (proposals or not), the smallest valid-from not yet checked
var cursors: Map[(SynchronizerId, Boolean), CantonTimestamp] = Map.empty

while (true) {
  Thread.sleep(pollIntervalSeconds * 1000)
  try {
    val registered = participant.synchronizers
      .list_registered()
      .flatMap { case (config, psid, _) =>
        psid.toOption.map(id => config.synchronizerAlias.unwrap -> id.logical)
      }
      .toMap
    val missingAliases = expectedSynchronizerAliases.filterNot(registered.contains)
    if (missingAliases.nonEmpty) {
      logger.warn(
        s"Synchronizer(s) ${missingAliases.toSeq.sorted.mkString(", ")} are not registered " +
          s"on the participant with a known synchronizer id, so they are not monitored"
      )
    }
    val synchronizerIds = expectedSynchronizerAliases.toSeq.sorted.flatMap(registered.get)
    val polled = for {
      synchronizerId <- synchronizerIds
      proposals <- Seq(false, true)
    } yield {
      val from = cursors.getOrElse((synchronizerId, proposals), startedAt)
      val kind = if (proposals) "proposals" else "transactions"
      logger.info(s"Querying topology $kind on $synchronizerId from valid-from $from")
      val queryStart = System.nanoTime()
      val newTransactions = participant.topology.transactions
        .list(
          store = TopologyStoreId.Synchronizer(synchronizerId),
          proposals = proposals,
          timeQuery = TimeQuery.Range(Some(from), None),
          operation = None,
        )
        .result
      val queryMillis = (System.nanoTime() - queryStart) / 1000000
      if (queryMillis > slowQueryThresholdSeconds * 1000) {
        logger.warn(
          s"Querying topology $kind on $synchronizerId from valid-from $from took ${queryMillis}ms, " +
            s"more than the threshold of ${slowQueryThresholdSeconds}s"
        )
      }
      // `from` is inclusive, so the cursor is moved past the last valid-from seen to exclude it
      val nextFrom =
        newTransactions.map(_.validFrom.value).maxOption.fold(from)(_.immediateSuccessor)
      ((synchronizerId, proposals), newTransactions, nextFrom)
    }

    for {
      ((synchronizerId, _), newTransactions, _) <- polled
      stored <- newTransactions
    } {
      val matchedKeys = stored.transaction.signatures
        .map(_.authorizingLongTermKey.toProtoPrimitive)
        .toSet
        .intersect(trackedKeys)
      if (matchedKeys.nonEmpty) {
        val kind = if (stored.transaction.isProposal) "proposal" else "transaction"
        val message = s"Topology $kind on $synchronizerId signed by tracked key(s) " +
          s"${matchedKeys.toSeq.sorted.mkString(", ")}: $stored"
        stored.mapping match {
          // Unhosting all parties is usually the first step of a genuine recovery, so it is not warned about to avoid noise
          case p2p: PartyToParticipant if p2p.participants.isEmpty =>
            logger.info(s"Ignoring unhosting of ${p2p.partyId}. $message")
          case _ => logger.warn(message)
        }
      }
    }

    cursors = cursors ++ polled.map { case (cursor, _, nextFrom) => cursor -> nextFrom }
  } catch {
    case scala.util.control.NonFatal(e) =>
      logger.warn(
        s"Topology key monitor failed to poll participant, retrying in ${pollIntervalSeconds}s",
        e,
      )
  }
}

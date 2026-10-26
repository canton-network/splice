// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.store.TimeQuery

def requireEnv(name: String): String =
  sys.env.getOrElse(name, sys.error(s"Environment variable $name must be set"))

val trackedKeys: Set[String] = requireEnv("TOPOLOGY_KEY_MONITOR_KEYS")
  .split(",")
  .map(_.trim)
  .filter(_.nonEmpty)
  .toSet
if (trackedKeys.isEmpty) {
  sys.error("TOPOLOGY_KEY_MONITOR_KEYS must contain at least one key fingerprint")
}
val pollIntervalSeconds = requireEnv("TOPOLOGY_KEY_MONITOR_POLL_INTERVAL").toLong

logger.info(
  s"Starting topology key monitor for keys ${trackedKeys.toSeq.sorted.mkString(", ")}, " +
    s"polling every ${pollIntervalSeconds}s"
)

val startedAt = CantonTimestamp.now()
// Per synchronizer, the smallest valid-from not yet checked
var cursors: Map[SynchronizerId, CantonTimestamp] = Map.empty

while (true) {
  Thread.sleep(pollIntervalSeconds * 1000)
  try {
    val synchronizerIds = participant.synchronizers.list_connected().map(_.synchronizerId)
    val polled = synchronizerIds.map { synchronizerId =>
      val from = cursors.getOrElse(synchronizerId, startedAt)
      val newTransactions = participant.topology.transactions
        .list(
          store = TopologyStoreId.Synchronizer(synchronizerId),
          timeQuery = TimeQuery.Range(Some(from), None),
          operation = None,
        )
        .result
      // `from` is inclusive, so the cursor is moved past the last valid-from seen to exclude it
      val nextFrom =
        newTransactions.map(_.validFrom.value).maxOption.fold(from)(_.immediateSuccessor)
      (synchronizerId, newTransactions, nextFrom)
    }

    for {
      (synchronizerId, newTransactions, _) <- polled
      stored <- newTransactions
    } {
      val matchedKeys = stored.transaction.signatures
        .map(_.authorizingLongTermKey.toProtoPrimitive)
        .toSet
        .intersect(trackedKeys)
      if (matchedKeys.nonEmpty) {
        logger.warn(
          s"Topology transaction on $synchronizerId signed by tracked key(s) " +
            s"${matchedKeys.toSeq.sorted.mkString(", ")}: $stored"
        )
      }
    }

    cursors = cursors ++ polled.map { case (synchronizerId, _, nextFrom) =>
      synchronizerId -> nextFrom
    }
  } catch {
    case scala.util.control.NonFatal(e) =>
      logger.warn(
        s"Topology key monitor failed to poll participant, retrying in ${pollIntervalSeconds}s",
        e,
      )
  }
}

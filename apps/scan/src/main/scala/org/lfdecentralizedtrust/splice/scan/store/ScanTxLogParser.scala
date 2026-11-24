// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import cats.Monoid
import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequest,
  DsoRules_CloseVoteRequestResult,
}
import org.lfdecentralizedtrust.splice.store.TxLogStore
import org.lfdecentralizedtrust.splice.store.events.DsoRulesCloseVoteRequest
import org.lfdecentralizedtrust.splice.util.{EventId, ExerciseNode}

import scala.collection.immutable
import scala.jdk.CollectionConverters.*

class ScanTxLogParser(
    override val loggerFactory: NamedLoggerFactory
) extends TxLogStore.Parser[
      TxLogEntry
    ]
    with NamedLogging {

  import ScanTxLogParser.*

  // ignoreUnexpectedAmuletCreateArchive disables the warning when we
  // hit a bare create/archive of an amulet contract.  We use this for
  // parsing contracts that used to have a `AmuletRules_Transfer`
  // child node but no longer do. This is in particular true for all
  // token standard choices after the change to 24h submission delay
  // change.  This allows us to parse the children of e.g. a token
  // standard transfer with ignoreUnexpectedAmuletCreateArchive=true
  // which then works for both the version with the
  // AmuletRules_Transfer child and the one without.  If we get a
  // transfer event as a child, we know we parsed the old version
  // whereas if we get no transfer event child we need to construct
  // one directly for the token standard choice but can make more
  // assumptions, in particular, we know that there are no fees as
  // those have been disabled before the 24h submission change takes
  // effect.
  private def parseTree(
      tree: Transaction,
      synchronizerId: SynchronizerId,
      root: Event,
      ignoreUnexpectedAmuletCreateArchive: Boolean,
  )(implicit
      tc: TraceContext
  ): State = {
    root match {
      case exercised: ExercisedEvent =>
        val eventId = EventId.prefixedFromUpdateIdAndNodeId(
          tree.getUpdateId,
          exercised.getNodeId,
        )
        exercised match {
          case DsoRulesCloseVoteRequest(node) =>
            State.fromCloseVoteRequest(eventId, node)
          case _ =>
            parseTrees(
              tree,
              synchronizerId,
              tree.getChildNodeIds(exercised).asScala.toList,
              ignoreUnexpectedAmuletCreateArchive,
            )
        }

      case _: CreatedEvent =>
        State.empty

      case _ =>
        sys.error("The above match should be exhaustive")
    }
  }

  private def parseTrees(
      tree: Transaction,
      synchronizerId: SynchronizerId,
      rootsNodeIds: List[Integer],
      ignoreUnexpectedAmuletCreateArchive: Boolean,
  )(implicit
      tc: TraceContext
  ): State = {
    val roots = rootsNodeIds.map(tree.getEventsById.get(_))
    roots.foldMap(parseTree(tree, synchronizerId, _, ignoreUnexpectedAmuletCreateArchive))
  }

  override def tryParse(tx: Transaction, domain: SynchronizerId)(implicit
      tc: TraceContext
  ): Seq[TxLogEntry] = {
    val ret = parseTrees(
      tx,
      domain,
      tx.getRootNodeIds.asScala.toList,
      ignoreUnexpectedAmuletCreateArchive = false,
    ).entries
    ret
  }

  override def error(
      offset: Long,
      eventId: String,
      synchronizerId: SynchronizerId,
  ): Option[TxLogEntry] =
    Some(
      ErrorTxLogEntry(
        eventId = eventId
      )
    )
}

object ScanTxLogParser {

  private case class State(
      entries: immutable.Queue[TxLogEntry]
  ) {
    def appended(other: State): State = State(
      entries = entries.appendedAll(other.entries)
    )
  }

  private object State {
    def apply(entry: TxLogEntry): State = {
      State(immutable.Queue(entry))
    }

    def empty: State = State(immutable.Queue.empty)

    implicit val stateMonoid: Monoid[State] = new Monoid[State] {
      override val empty: State = State(immutable.Queue.empty)

      override def combine(a: State, b: State): State =
        a.appended(b)
    }

    def fromCloseVoteRequest(
        eventId: String,
        node: ExerciseNode[DsoRules_CloseVoteRequest, DsoRules_CloseVoteRequestResult],
    ): State = {
      State(
        immutable.Queue(
          VoteRequestTxLogEntry(
            eventId,
            result = Some(node.result.value),
          )
        )
      )
    }
  }
}

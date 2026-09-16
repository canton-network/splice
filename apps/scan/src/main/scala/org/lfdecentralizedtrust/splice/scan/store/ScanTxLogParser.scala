// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import cats.Monoid
import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequest,
  DsoRules_CloseVoteRequestResult,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.transfercommandresult.{
  TransferCommandResultFailure,
  TransferCommandResultSuccess,
}
import org.lfdecentralizedtrust.splice.history.*
import org.lfdecentralizedtrust.splice.store.TxLogStore
import org.lfdecentralizedtrust.splice.store.events.DsoRulesCloseVoteRequest
import org.lfdecentralizedtrust.splice.util.SpliceUtil.dollarsToCC
import org.lfdecentralizedtrust.splice.util.{Codec, EventId, ExerciseNode}

import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal

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
    // TODO(DACH-NY/canton-network-node#2930) add more checks on the nodes, at least that the DSO party is correct
    root match {
      case exercised: ExercisedEvent =>
        val eventId = EventId.prefixedFromUpdateIdAndNodeId(
          tree.getUpdateId,
          exercised.getNodeId,
        )
        exercised match {
          case DsoRulesCloseVoteRequest(node) =>
            State.fromCloseVoteRequest(eventId, node)
          case ExternalPartyAmuletRules_CreateTransferCommand(node) =>
            State.fromCreateTransferCommand(eventId, node)
          case TransferCommand_Send(node) =>
            val state = parseTrees(
              tree,
              synchronizerId,
              tree.getChildNodeIds(exercised).asScala.toList,
              ignoreUnexpectedAmuletCreateArchive,
            )
            val transferCommandState = State.fromTransferCommand_Send(eventId, exercised, node)
            state.appended(transferCommandState)
          case TransferCommand_Withdraw(node) =>
            State.fromTransferCommand_Withdraw(eventId, exercised, node)
          case TransferCommand_Expire(node) =>
            State.fromTransferCommand_Expire(eventId, exercised, node)
          case _ =>
            parseTrees(
              tree,
              synchronizerId,
              tree.getChildNodeIds(exercised).asScala.toList,
              ignoreUnexpectedAmuletCreateArchive,
            )
        }

      case created: CreatedEvent =>
        created match {
          case OpenMiningRoundCreate(round) =>
            State.fromOpenMiningRoundCreate(
              EventId.prefixedFromUpdateIdAndNodeId(
                tree.getUpdateId,
                root.getNodeId,
              ),
              synchronizerId,
              round,
            )
          case ClosedMiningRoundCreate(round) =>
            State.fromClosedMiningRoundCreate(tree, root, synchronizerId, round)
          case _ => State.empty
        }

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

    def fromOpenMiningRoundCreate(
        eventId: String,
        synchronizerId: SynchronizerId,
        round: OpenMiningRoundCreate.ContractType,
    ): State = {
      val config = round.payload.transferConfigUsd
      val amuletPrice = round.payload.amuletPrice
      val newEntry = OpenMiningRoundTxLogEntry(
        eventId = eventId,
        domainId = synchronizerId,
        round = round.payload.round.number,
        amuletCreateFee = dollarsToCC(config.createFee.fee, amuletPrice),
        holdingFee = dollarsToCC(config.holdingFee.rate, amuletPrice),
        lockHolderFee = dollarsToCC(config.lockHolderFee.fee, amuletPrice),
        transferFee = Some(
          SteppedRate(
            initialRate = config.transferFee.initialRate,
            steps = config.transferFee.steps.asScala.toSeq
              .map(step =>
                SteppedRate.Step(
                  from = dollarsToCC(step._1, amuletPrice),
                  rate = step._2,
                )
              ),
          )
        ),
      )

      State(newEntry)
    }

    def fromClosedMiningRoundCreate(
        tx: Transaction,
        event: Event,
        synchronizerId: SynchronizerId,
        round: ClosedMiningRoundCreate.ContractType,
    ): State = {
      val newEntry = ClosedMiningRoundTxLogEntry(
        eventId = EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, event.getNodeId),
        domainId = synchronizerId,
        round = round.payload.round.number,
        effectiveAt = Some(tx.getEffectiveAt),
      )

      State(newEntry)
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

    def fromCreateTransferCommand(
        eventId: String,
        node: ExerciseNode[
          ExternalPartyAmuletRules_CreateTransferCommand.Arg,
          ExternalPartyAmuletRules_CreateTransferCommand.Res,
        ],
    ): State = {
      State(
        immutable.Queue(
          TransferCommandTxLogEntry(
            eventId,
            contractId = Codec.encodeContractId(node.result.value.transferCommandCid),
            sender = PartyId.tryFromProtoPrimitive(node.argument.value.sender),
            nonce = node.argument.value.nonce,
            status = TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
          )
        )
      )
    }

    def fromTransferCommand_Send(
        eventId: String,
        exercised: ExercisedEvent,
        node: ExerciseNode[TransferCommand_Send.Arg, TransferCommand_Send.Res],
    ): State = {
      State(
        immutable.Queue(
          TransferCommandTxLogEntry(
            eventId = eventId,
            contractId = exercised.getContractId,
            sender = PartyId.tryFromProtoPrimitive(node.result.value.sender),
            nonce = node.result.value.nonce,
            status = node.result.value.result match {
              case failure: TransferCommandResultFailure =>
                TransferCommandTxLogEntry.Status.Failed(
                  TransferCommandFailed(failure.reason.toString)
                )
              case _: TransferCommandResultSuccess =>
                TransferCommandTxLogEntry.Status.Sent(TransferCommandSent())
              case e =>
                sys.error(s"TransferCommandResult must be either failure or success but got: $e")
            },
          )
        )
      )
    }

    def fromTransferCommand_Withdraw(
        eventId: String,
        exercised: ExercisedEvent,
        node: ExerciseNode[TransferCommand_Withdraw.Arg, TransferCommand_Withdraw.Res],
    ): State = {
      State(
        immutable.Queue(
          TransferCommandTxLogEntry(
            eventId = eventId,
            contractId = exercised.getContractId,
            sender = PartyId.tryFromProtoPrimitive(node.result.value.sender),
            nonce = node.result.value.nonce,
            status = TransferCommandTxLogEntry.Status.Withdrawn(TransferCommandWithdrawn()),
          )
        )
      )
    }

    def fromTransferCommand_Expire(
        eventId: String,
        exercised: ExercisedEvent,
        node: ExerciseNode[TransferCommand_Expire.Arg, TransferCommand_Expire.Res],
    ): State = {
      State(
        immutable.Queue(
          TransferCommandTxLogEntry(
            eventId = eventId,
            contractId = exercised.getContractId,
            sender = PartyId.tryFromProtoPrimitive(node.result.value.sender),
            nonce = node.result.value.nonce,
            status = TransferCommandTxLogEntry.Status.Expired(TransferCommandExpired()),
          )
        )
      )
    }
  }
}

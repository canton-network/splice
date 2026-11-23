// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import org.lfdecentralizedtrust.splice.store.StoreErrors

import com.digitalasset.canton.config.CantonRequireTypes.String3

trait TxLogEntry extends Product with Serializable {
  // Scan store uses the eventId for pagination
  def eventId: String
}

object TxLogEntry extends StoreErrors {

  object EntryType {
    val ErrorTxLogEntry = String3.tryCreate("err")
    val VoteRequestTxLogEntry = String3.tryCreate("vot")
    // The following entry types correspond to entries that were removed from `scan_tx_log.proto`
    // Those entries might still exist in databases, but we don't produce new ones and we don't read them.
    // The values are only kept for documentation purposes.
    val Unused_SvRewardCollectedTxLogEntry = String3.tryCreate("src")
    val Unused_BalanceChangeTxLogEntry = String3.tryCreate("bac")
    val Unused_ExtraTrafficPurchaseTxLogEntry = String3.tryCreate("etp")
    val Unused_AppRewardTxLogEntry = String3.tryCreate("are")
    val Unused_MintTxLogEntry = String3.tryCreate("min")
    val Unused_TapTxLogEntry = String3.tryCreate("tap")
    val Unused_TransferTxLogEntry = String3.tryCreate("tra")
    val Unused_ValidatorRewardTxLogEntry = String3.tryCreate("vre")
    val Unused_SvRewardTxLogEntry = String3.tryCreate("sre")
    val Unused_AbortTransferInstructionTxLogEntry = String3.tryCreate("ati")
    val Unused_TransferCommandTxLogEntry = String3.tryCreate("trc")
    val Unused_ClosedMiningRoundTxLogEntry = String3.tryCreate("cmr")
    val Unused_OpenMiningRoundTxLogEntry = String3.tryCreate("omr")
  }

  def encode(entry: TxLogEntry): (String3, String) = {
    import scalapb.json4s.JsonFormat
    val entryType = entry match {
      case _: ErrorTxLogEntry => EntryType.ErrorTxLogEntry
      case _: VoteRequestTxLogEntry => EntryType.VoteRequestTxLogEntry
      case _ => throw txEncodingFailed()
    }
    val jsonValue = entry match {
      case e: scalapb.GeneratedMessage => JsonFormat.toJsonString(e)
      case _ => throw txEncodingFailed()
    }
    (entryType, jsonValue)
  }
  def decode(entryType: String3, json: String): TxLogEntry = {
    import scalapb.json4s.JsonFormat.fromJsonString as from
    try {
      entryType match {
        case EntryType.ErrorTxLogEntry => from[ErrorTxLogEntry](json)
        case EntryType.VoteRequestTxLogEntry => from[VoteRequestTxLogEntry](json)
        case _ => throw txLogIsOfWrongType(entryType.str)
      }
    } catch {
      case _: RuntimeException => throw txDecodingFailed()
    }
  }
}

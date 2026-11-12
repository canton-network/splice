// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// Decodes an `onboarding-state.zst` written by the SV app's PeriodicTopologySnapshotTrigger into
// JSONL, with one line per topology transaction. Parsing is done by Canton's own
// `OnboardingStateForSequencerV2.parseDelimitedFromTrusted`, so all versioned-wrapper handling and
// mapping validation is Canton's.
//
// Usage:
//   SNAPSHOT_IN=/path/onboarding-state.zst SNAPSHOT_OUT=/path/out.jsonl \
//   SNAPSHOT_MEMBERS_OUT=/path/members.jsonl \
//   SNAPSHOT_SCHEMA_OUT=/path/out-schema.json SNAPSHOT_MEMBERS_SCHEMA_OUT=/path/members-schema.json \
//     canton run scripts/topology-snapshot-to-jsonl.sc --no-tty \
//     -C canton.remote-participants.unused.admin-api.port=1,canton.remote-participants.unused.ledger-api.port=2
//
// No nodes need to be running. Canton refuses to start without at least one node in its config,
// so the `-C` flag defines a dummy remote participant that the script never connects to.
// The input is streamed, so a small heap is enough (e.g. JAVA_OPTS=-Xmx256m).
//
// Outputs:
//   SNAPSHOT_OUT          one line per topology transaction
//   SNAPSHOT_MEMBERS_OUT  one line per sequencer member, with its status, its latest traffic
//                         purchase and consumption, and the time of its last event
//   SNAPSHOT_SCHEMA_OUT, SNAPSHOT_MEMBERS_SCHEMA_OUT
//                         BigQuery schemas for the two files, for use with e.g.
//                         bq load --replace --source_format=NEWLINE_DELIMITED_JSON \
//                           <dataset>.<table> <out.jsonl> <out-schema.json>
//
// Optional env vars:
//   SNAPSHOT_HEADER_OUT  file for the static synchronizer parameters + the rest of the
//                        sequencer snapshot (JSON), without the per-member data
//   SNAPSHOT_LIMIT       stop after this many messages (useful for debugging)

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.synchronizer.sequencer.{
  OnboardingStateForSequencerV2,
  SequencerMemberStatus,
  SequencerSnapshot,
}
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.topology.store.StoredTopologyTransaction.GenericStoredTopologyTransaction
import com.digitalasset.canton.version.ProtoVersion
import com.github.luben.zstd.ZstdInputStream
import io.circe.Json
import scalapb.GeneratedMessage
import scalapb.descriptors._

import java.io.{BufferedInputStream, BufferedWriter, FileInputStream, FileWriter}
import java.util.Base64
import scala.collection.mutable

def requireEnv(name: String): String =
  sys.env.getOrElse(name, sys.error(s"Environment variable $name must be set"))

val inPath = requireEnv("SNAPSHOT_IN")
val outPath = requireEnv("SNAPSHOT_OUT")
val membersOutPath = requireEnv("SNAPSHOT_MEMBERS_OUT")
val txSchemaOutPath = requireEnv("SNAPSHOT_SCHEMA_OUT")
val membersSchemaOutPath = requireEnv("SNAPSHOT_MEMBERS_SCHEMA_OUT")
val headerOutPath = sys.env.get("SNAPSHOT_HEADER_OUT")
val limit = sys.env.get("SNAPSHOT_LIMIT").map(_.toLong).getOrElse(Long.MaxValue)

// Generic ScalaPB -> JSON, following the protobuf JSON mapping conventions with proto field names:
// default scalar values are omitted, 64-bit ints are strings, bytes are base64, enums are names,
// and Duration / Timestamp / wrapper types use their special JSON representations.
def scalarToJson(v: PValue): Json = v match {
  case PInt(i) => Json.fromInt(i)
  case PLong(l) => Json.fromString(l.toString)
  case PString(s) => Json.fromString(s)
  case PBoolean(b) => Json.fromBoolean(b)
  case PFloat(f) => Json.fromFloatOrString(f)
  case PDouble(d) => Json.fromDoubleOrString(d)
  case PByteString(b) => Json.fromString(Base64.getEncoder.encodeToString(b.toByteArray))
  case PEnum(e) => Json.fromString(e.name)
  case other => sys.error(s"Not a scalar: $other")
}

def isDefault(v: PValue): Boolean = v match {
  case PEmpty => true
  case PInt(i) => i == 0
  case PLong(l) => l == 0L
  case PString(s) => s.isEmpty
  case PBoolean(b) => !b
  case PFloat(f) => f == 0f
  case PDouble(d) => d == 0d
  case PByteString(b) => b.isEmpty
  case PEnum(e) => e.number == 0
  case PRepeated(xs) => xs.isEmpty
  case _: PMessage => false // a set message field is emitted even if all its fields are defaults
}

def fieldLong(fields: Map[FieldDescriptor, PValue], name: String): Long =
  fields
    .collectFirst {
      case (fd, PLong(l)) if fd.name == name => l
      case (fd, PInt(i)) if fd.name == name => i.toLong
    }
    .getOrElse(0L)

// Proto JSON formats fractional seconds with 0, 3, 6 or 9 digits
def fractionalSeconds(nanos: Long): String =
  if (nanos == 0) ""
  else if (nanos % 1000000 == 0) f".${nanos / 1000000}%03d"
  else if (nanos % 1000 == 0) f".${nanos / 1000}%06d"
  else f".$nanos%09d"

def messageToJson(descriptor: Descriptor, fields: Map[FieldDescriptor, PValue]): Json =
  descriptor.fullName match {
    case "google.protobuf.Duration" =>
      val seconds = fieldLong(fields, "seconds")
      val nanos = fieldLong(fields, "nanos")
      val sign = if (seconds < 0 || nanos < 0) "-" else ""
      Json.fromString(s"$sign${math.abs(seconds)}${fractionalSeconds(math.abs(nanos))}s")
    case "google.protobuf.Timestamp" =>
      Json.fromString(
        java.time.Instant
          .ofEpochSecond(fieldLong(fields, "seconds"), fieldLong(fields, "nanos"))
          .toString
      )
    case wrapper if wrapper.startsWith("google.protobuf.") && wrapper.endsWith("Value") =>
      // Int64Value, StringValue, etc.: emitted as their bare value, including defaults
      val valueField =
        descriptor.findFieldByName("value").getOrElse(sys.error(s"No value in $wrapper"))
      fields.get(valueField) match {
        case Some(v) if v != PEmpty => scalarToJson(v)
        case _ =>
          scalarToJson(valueField.scalaType match {
            case ScalaType.Long => PLong(0L)
            case ScalaType.Int => PInt(0)
            case ScalaType.Boolean => PBoolean(false)
            case ScalaType.Float => PFloat(0f)
            case ScalaType.Double => PDouble(0d)
            case ScalaType.String => PString("")
            case ScalaType.ByteString => PByteString(com.google.protobuf.ByteString.EMPTY)
            case other => sys.error(s"Unexpected wrapper type $other")
          })
      }
    case _ =>
      Json.fromFields(fields.toSeq.sortBy(_._1.number).collect {
        case (fd, v) if !isDefault(v) => fd.name -> valueToJson(fd, v)
      })
  }

def valueToJson(fd: FieldDescriptor, v: PValue): Json = v match {
  case PRepeated(xs) => Json.fromValues(xs.map(valueToJson(fd, _)))
  case PMessage(fields) =>
    fd.scalaType match {
      case ScalaType.Message(d) => messageToJson(d, fields)
      case other => sys.error(s"Message value for non-message field ${fd.fullName}: $other")
    }
  case scalar => scalarToJson(scalar)
}

def protoToJson(m: GeneratedMessage): Json =
  messageToJson(m.companion.scalaDescriptor, m.toPMessage.value)

val stats = mutable.Map.empty[String, Long].withDefaultValue(0L)
val byCode = mutable.Map.empty[String, (Long, Long)].withDefaultValue((0L, 0L))

// BigQuery table schemas for the two JSONL outputs. Every row written is checked against its schema's
// column names, so the rows and the schema files can't drift apart.
final case class Column(name: String, bqType: String, mode: String, description: String)

def required(name: String, bqType: String, description: String) =
  Column(name, bqType, "REQUIRED", description)
def nullable(name: String, bqType: String, description: String) =
  Column(name, bqType, "NULLABLE", description)

val txSchema = Seq(
  required(
    "row_number",
    "INTEGER",
    "0-based position of the transaction in the snapshot, which is the order of the topology store",
  ),
  required("sequenced", "TIMESTAMP", "When the transaction was sequenced"),
  required("valid_from", "TIMESTAMP", "Effective time from which the transaction is valid"),
  required(
    "batch_idx",
    "INTEGER",
    "Position among the transactions with the same valid_from; (valid_from, batch_idx) is unique." +
      " Not in the snapshot itself: rebuilt from the row order, as Canton does when importing it",
  ),
  nullable(
    "valid_until",
    "TIMESTAMP",
    "Effective time until which it was valid; null if still valid",
  ),
  nullable("rejection_reason", "STRING", "Set if the transaction was rejected"),
  required("operation", "STRING", "Replace or Remove"),
  required("serial", "INTEGER", "Serial of the transaction for its mapping's unique key"),
  required("mapping_code", "STRING", "Mapping type, e.g. party_to_participant"),
  required("namespace", "STRING", "Namespace of the mapping"),
  nullable("uid", "STRING", "Unique identifier of the mapping, if it has one"),
  required(
    "mapping",
    "JSON",
    "The mapping as proto JSON, in the proto version of the transaction's protocol version",
  ),
  required(
    "signed_transaction",
    "JSON",
    "The full signed transaction (transaction, signatures, proposal flag) as proto JSON, in the" +
      " proto version of its protocol version, with the transaction decoded instead of serialized" +
      " bytes",
  ),
  required("is_proposal", "BOOLEAN", "Whether the transaction is a proposal"),
  Column("signed_by", "STRING", "REPEATED", "Fingerprints of the long-term keys that signed it"),
  required("tx_hash", "STRING", "Canton's topology transaction hash"),
)

val membersSchema = Seq(
  required("member", "STRING", "Sequencer member, e.g. PAR::name::fingerprint"),
  required("member_type", "STRING", "PAR, MED or SEQ"),
  required(
    "status",
    "STRING",
    "active, disabled, onboarding, or unknown if missing from the status",
  ),
  nullable("registered_at", "TIMESTAMP", "When the member was registered on the sequencer"),
  nullable("last_acknowledged", "TIMESTAMP", "Last timestamp the member acknowledged"),
  nullable("traffic_purchased_serial", "INTEGER", "Serial of the latest traffic purchase"),
  nullable(
    "extra_traffic_purchased",
    "INTEGER",
    "Total extra traffic purchased; 9223372036854775807 means unlimited",
  ),
  nullable("traffic_purchased_at", "TIMESTAMP", "When the latest traffic purchase was sequenced"),
  nullable("extra_traffic_consumed", "INTEGER", "Total extra traffic consumed"),
  nullable("base_traffic_remainder", "INTEGER", "Remaining base traffic"),
  nullable("last_consumed_cost", "INTEGER", "Cost of the last consumed event"),
  nullable("traffic_consumed_at", "TIMESTAMP", "When traffic consumption was last updated"),
  nullable("last_event_at", "TIMESTAMP", "Last event the sequencer delivered to the member"),
)

def schemaJson(columns: Seq[Column]): Json =
  Json.fromValues(columns.map { c =>
    Json.obj(
      "name" -> Json.fromString(c.name),
      "type" -> Json.fromString(c.bqType),
      "mode" -> Json.fromString(c.mode),
      "description" -> Json.fromString(c.description),
    )
  })

def checkColumns(row: Json, columns: Seq[Column]): Json = {
  val obj = row.asObject.getOrElse(sys.error(s"Row is not an object: $row"))
  if (obj.keys.toSeq != columns.map(_.name))
    sys.error(s"Row columns ${obj.keys.mkString(",")} don't match the schema")
  columns.filter(_.mode == "REQUIRED").find(c => obj(c.name).forall(_.isNull)).foreach { c =>
    sys.error(s"Required column ${c.name} is null in row $row")
  }
  row
}

def writeFile(path: String, content: String): Unit = {
  val w = new FileWriter(path)
  try w.write(content)
  finally w.close()
}

// The snapshot doesn't contain the topology store's batch_idx, but lists the transactions in store
// order. Rebuild it the way Canton does on import (DbTopologyStore.bulkInsert): the position within
// each run of transactions with the same valid_from.
var lastValidFrom: Option[CantonTimestamp] = None
var batchIdx = -1L
def nextBatchIdx(validFrom: CantonTimestamp): Long = {
  lastValidFrom match {
    case Some(last) if validFrom == last => batchIdx += 1
    case Some(last) if validFrom < last =>
      sys.error(s"Snapshot is unordered: valid_from $validFrom comes after $last")
    case _ => batchIdx = 0
  }
  lastValidFrom = Some(validFrom)
  batchIdx
}

// Canton picks a class's proto version from the protocol version it was created for, and new
// protocol versions bring new protos (e.g. TopologyTransaction v31 for PV37, which adds hosting
// states to PartyToParticipant). Serialize with that same version instead of a fixed one, so the
// JSON shows what is in the snapshot, and a newer snapshot doesn't fail or lose data.
def versioned(what: String, version: ProtoVersion)(
    byVersion: PartialFunction[Int, Either[String, GeneratedMessage]]
): GeneratedMessage =
  byVersion.lift(version.v) match {
    case Some(Right(p)) => p
    case Some(Left(err)) => sys.error(s"Failed to serialize $what: $err")
    case None => sys.error(s"Unsupported proto version $version for $what; update this script")
  }

def txToJson(stored: GenericStoredTopologyTransaction, rowNumber: Long): Json = {
  val signed = stored.transaction
  val tx = signed.transaction
  // TopologyMapping proto is a oneof wrapper with exactly one field set, e.g. {"party_to_participant": {...}}
  val transactionProto = versioned(s"transaction $tx", tx.protoVersion) {
    case 30 => tx.toProtoV30
    case 31 => tx.toProtoV31
  }
  val transactionJson = protoToJson(transactionProto)
  val (mappingCode, mappingJson) = transactionJson.hcursor
    .downField("mapping")
    .focus
    .flatMap(_.asObject)
    .flatMap(_.toList.headOption)
    .getOrElse(sys.error(s"Transaction without a mapping: $tx"))
  val active = stored.validUntil.isEmpty
  val (all, act) = byCode(mappingCode)
  byCode(mappingCode) = (all + 1, act + (if (active) 1 else 0))
  if (signed.isProposal) stats("proposals") += 1
  if (stored.rejectionReason.isDefined) stats("rejected") += 1
  // The signed transaction proto holds the transaction as serialized bytes; replace those with the
  // decoded transaction so the whole thing is queryable JSON.
  val signedProto = versioned(s"signed transaction $signed", signed.protoVersion) { case 30 =>
    Right(signed.toProtoV30)
  }
  val signedJson = protoToJson(signedProto).mapObject(_.add("transaction", transactionJson))

  Json.obj(
    "row_number" -> Json.fromLong(rowNumber),
    "sequenced" -> Json.fromString(stored.sequenced.value.toInstant.toString),
    "valid_from" -> Json.fromString(stored.validFrom.value.toInstant.toString),
    "batch_idx" -> Json.fromLong(nextBatchIdx(stored.validFrom.value)),
    "valid_until" -> stored.validUntil.fold(Json.Null)(t =>
      Json.fromString(t.value.toInstant.toString)
    ),
    "rejection_reason" -> stored.rejectionReason.fold(Json.Null)(r => Json.fromString(r.str)),
    "operation" -> Json.fromString(tx.operation.toString),
    "serial" -> Json.fromInt(tx.serial.value),
    "mapping_code" -> Json.fromString(mappingCode),
    "namespace" -> Json.fromString(tx.mapping.namespace.toProtoPrimitive),
    "uid" -> tx.mapping.maybeUid.fold(Json.Null)(u => Json.fromString(u.toProtoPrimitive)),
    "mapping" -> mappingJson,
    "signed_transaction" -> signedJson,
    "is_proposal" -> Json.fromBoolean(signed.isProposal),
    "signed_by" -> Json.fromValues(
      signed.signatures.toSeq
        .map(_.signature.authorizingLongTermKey.toProtoPrimitive)
        .distinct
        .sorted
        .map(Json.fromString)
    ),
    // Canton's topology transaction hash, as shown by the console's topology commands
    "tx_hash" -> Json.fromString(tx.hash.hash.toHexString),
  )
}

def tsJson(ts: CantonTimestamp): Json = Json.fromString(ts.toInstant.toString)
def optTsJson(ts: Option[CantonTimestamp]): Json = ts.fold(Json.Null)(tsJson)

// One flat row per member, joining the sequencer's member status, the member's latest traffic
// purchase and consumption, and the timestamp of the last event the sequencer delivered to it.
// A member can be missing from any of these sources, in which case those columns are null.
def memberRows(snapshot: SequencerSnapshot): Seq[Json] = {
  val statuses = snapshot.status.members.toSeq.groupBy(_.member)
  val purchased = snapshot.trafficPurchased.groupBy(_.member)
  val consumed = snapshot.trafficConsumed.groupBy(_.member)
  val members =
    (statuses.keySet ++ purchased.keySet ++ consumed.keySet ++ snapshot.previousTimestamps.keySet).toSeq
      .sortBy(_.toProtoPrimitive)
  members.map { member =>
    def single[A](entries: Map[Member, Seq[A]], name: String): Option[A] =
      entries.get(member).map {
        case Seq(a) => a
        case many => sys.error(s"Member $member has ${many.size} $name entries")
      }
    val (status, registeredAt, lastAcknowledged) = single(statuses, "status") match {
      case Some(SequencerMemberStatus.Active(_, registered, acked)) =>
        ("active", Some(registered), acked)
      case Some(SequencerMemberStatus.Disabled(_, registered, acked)) =>
        ("disabled", registered, acked)
      case Some(SequencerMemberStatus.Onboarding(_)) => ("onboarding", None, None)
      case None => ("unknown", None, None)
    }
    if (status == "unknown") stats("members_without_status") += 1
    val p = single(purchased, "traffic purchased")
    val c = single(consumed, "traffic consumed")
    Json.obj(
      "member" -> Json.fromString(member.toProtoPrimitive),
      "member_type" -> Json.fromString(member.code.threeLetterId.unwrap),
      "status" -> Json.fromString(status),
      "registered_at" -> optTsJson(registeredAt),
      "last_acknowledged" -> optTsJson(lastAcknowledged),
      "traffic_purchased_serial" -> p.fold(Json.Null)(x => Json.fromInt(x.serial.value)),
      "extra_traffic_purchased" -> p.fold(Json.Null)(x =>
        Json.fromLong(x.extraTrafficPurchased.value)
      ),
      "traffic_purchased_at" -> optTsJson(p.map(_.sequencingTimestamp)),
      "extra_traffic_consumed" -> c.fold(Json.Null)(x =>
        Json.fromLong(x.extraTrafficConsumed.value)
      ),
      "base_traffic_remainder" -> c.fold(Json.Null)(x =>
        Json.fromLong(x.baseTrafficRemainder.value)
      ),
      "last_consumed_cost" -> c.fold(Json.Null)(x => Json.fromLong(x.lastConsumedCost.value)),
      "traffic_consumed_at" -> optTsJson(c.map(_.sequencingTimestamp)),
      // timestamp of the last event the sequencer delivered to this member
      "last_event_at" -> optTsJson(snapshot.previousTimestamps.get(member).flatten),
    )
  }
}

writeFile(txSchemaOutPath, schemaJson(txSchema).spaces2)
writeFile(membersSchemaOutPath, schemaJson(membersSchema).spaces2)

val in = new BufferedInputStream(new ZstdInputStream(new FileInputStream(inPath)), 1 << 20)
val out = new BufferedWriter(new FileWriter(outPath), 1 << 20)
val membersOut = new BufferedWriter(new FileWriter(membersOutPath))
val start = System.nanoTime()
var n = 0L
var txRows = 0L
try {
  var done = false
  while (!done && n < limit) {
    OnboardingStateForSequencerV2.parseDelimitedFromTrusted(in) match {
      case None => done = true
      case Some(Left(err)) => sys.error(s"Failed to parse message ${n + 1}: $err")
      case Some(Right(state)) =>
        n += 1
        state.topologySnapshot match {
          case Some(stored) =>
            out.write(checkColumns(txToJson(stored, txRows), txSchema).noSpaces)
            out.write('\n')
            txRows += 1
          case None =>
            stats("header_messages") += 1
            val rows = state.sequencerSnapshot.fold(Seq.empty[Json])(memberRows)
            rows.foreach { row =>
              membersOut.write(checkColumns(row, membersSchema).noSpaces)
              membersOut.write('\n')
            }
            stats("members") += rows.size.toLong
            headerOutPath.foreach { path =>
              // the per-member data is in the members file
              val header = Json.obj(
                "static_synchronizer_parameters" -> state.staticSynchronizerParameters
                  .fold(Json.Null) { p =>
                    protoToJson(versioned("static synchronizer parameters", p.protoVersion) {
                      case 30 => Right(p.toProtoV30)
                      case 31 => Right(p.toProtoV31)
                      case 32 => Right(p.toProtoV32)
                    })
                  },
                "sequencer_snapshot" -> state.sequencerSnapshot.fold(Json.Null) { snapshot =>
                  protoToJson(versioned("sequencer snapshot", snapshot.protoVersion) {
                    case 30 =>
                      snapshot.toProtoV30.map(s =>
                        s.copy(
                          status = s.status.map(_.copy(members = Seq.empty)),
                          trafficPurchased = Seq.empty,
                          trafficConsumed = Seq.empty,
                          memberPreviousTimestamps = Seq.empty,
                        )
                      )
                    case 31 =>
                      val s = snapshot.toProtoV31
                      Right(
                        s.copy(
                          status = s.status.map(_.copy(members = Seq.empty)),
                          trafficPurchased = Seq.empty,
                          trafficConsumed = Seq.empty,
                          memberPreviousTimestamps = Seq.empty,
                        )
                      )
                  })
                },
              )
              writeFile(path, header.spaces2)
            }
        }
        if (n % 500000 == 0)
          println(s"... $n messages, ${(System.nanoTime() - start) / 1000000000}s")
    }
  }
} finally {
  out.close()
  membersOut.close()
  in.close()
}

println(
  Json
    .obj(
      "messages" -> Json.fromLong(n),
      "elapsed_s" -> Json.fromLong((System.nanoTime() - start) / 1000000000),
      "stats" -> Json.fromFields(stats.toSeq.sortBy(_._1).map { case (k, v) =>
        k -> Json.fromLong(v)
      }),
      "by_mapping_code" -> Json.fromFields(byCode.toSeq.sortBy(-_._2._1).map {
        case (k, (all, act)) =>
          k -> Json.obj("all" -> Json.fromLong(all), "active" -> Json.fromLong(act))
      }),
    )
    .spaces2
)

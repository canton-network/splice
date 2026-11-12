// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// Decodes an `onboarding-state.zst` written by the SV app's PeriodicTopologySnapshotTrigger into
// JSONL, with one line per topology transaction. Parsing is done by Canton's own
// `OnboardingStateForSequencerV2.parseDelimitedFromTrusted`, so all versioned-wrapper handling and
// mapping validation is Canton's.
//
// Usage (no nodes needed):
//   SNAPSHOT_IN=/path/onboarding-state.zst SNAPSHOT_OUT=/path/out.jsonl \
//     canton run scripts/topology-snapshot-to-jsonl.sc
//
// Optional env vars:
//   SNAPSHOT_HEADER_OUT  file for the static synchronizer parameters + sequencer snapshot (JSON)
//   SNAPSHOT_LIMIT       stop after this many messages (for quick probing)

import com.digitalasset.canton.synchronizer.sequencer.OnboardingStateForSequencerV2
import com.digitalasset.canton.topology.store.StoredTopologyTransaction.GenericStoredTopologyTransaction
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

def txToJson(stored: GenericStoredTopologyTransaction): Json = {
  val signed = stored.transaction
  val tx = signed.transaction
  // TopologyMapping proto is a oneof wrapper with exactly one field set, e.g. {"party_to_participant": {...}}
  val (mappingCode, mappingJson) = tx.mapping.toProtoV30 match {
    case Right(p) =>
      protoToJson(p).asObject.flatMap(_.toList.headOption).getOrElse(("unknown", Json.Null))
    case Left(err) => sys.error(s"Failed to serialize mapping ${tx.mapping}: $err")
  }
  val active = stored.validUntil.isEmpty
  val (all, act) = byCode(mappingCode)
  byCode(mappingCode) = (all + 1, act + (if (active) 1 else 0))
  if (signed.isProposal) stats("proposals") += 1
  if (stored.rejectionReason.isDefined) stats("rejected") += 1

  Json.obj(
    "sequenced" -> Json.fromString(stored.sequenced.value.toInstant.toString),
    "valid_from" -> Json.fromString(stored.validFrom.value.toInstant.toString),
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

val in = new BufferedInputStream(new ZstdInputStream(new FileInputStream(inPath)), 1 << 20)
val out = new BufferedWriter(new FileWriter(outPath), 1 << 20)
val start = System.nanoTime()
var n = 0L
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
            out.write(txToJson(stored).noSpaces)
            out.write('\n')
          case None =>
            stats("header_messages") += 1
            headerOutPath.foreach { path =>
              val header = Json.obj(
                "static_synchronizer_parameters" -> state.staticSynchronizerParameters
                  .fold(Json.Null)(p => protoToJson(p.toProtoV30)),
                "sequencer_snapshot" -> state.sequencerSnapshot
                  .fold(Json.Null)(s => protoToJson(s.toProtoV31)),
              )
              val w = new FileWriter(path)
              try w.write(header.spaces2)
              finally w.close()
            }
        }
        if (n % 500000 == 0)
          println(s"... $n messages, ${(System.nanoTime() - start) / 1000000000}s")
    }
  }
} finally {
  out.close()
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

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.daml.ledger.javaapi.data as javaApi
import io.circe.Json

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Drops trailing `null` fields from the compact JSON encoding of daml records.
  *
  * The compact encoding is produced by the latest codegen, so values created before a daml upgrade
  * that appended optional fields get explicit `null`s for the new fields. Dropping trailing nulls
  * gives the same JSON regardless of which codegen version was used to encode a value.
  *
  * The compact JSON is untyped (a JSON object can be a record, a variant or a text map), so the
  * JSON is walked in parallel with the ledger API value it was encoded from.
  * Only records are trimmed. Variants (`{"tag": ..., "value": null}`) and text maps are left alone.
  */
object DropTrailingNulls {

  def trim(value: javaApi.Value, json: Json): Json = value match {
    case record: javaApi.DamlRecord => trimRecord(record, json)
    case variant: javaApi.Variant =>
      // `{"tag": ..., "value": ...}`: the payload is trimmed, but the `value` field is never dropped
      json.asObject.fold(json) { obj =>
        Json.fromJsonObject(
          obj("value").fold(obj)(payload => obj.add("value", trim(variant.getValue, payload)))
        )
      }
    case list: javaApi.DamlList =>
      json.asArray.fold(json) { arr =>
        val elems = list.toList(identity[javaApi.Value]).asScala.toList
        if (elems.sizeIs == arr.size) Json.fromValues(elems.zip(arr).map((trim _).tupled)) else json
      }
    case tmap: javaApi.DamlTextMap =>
      json.asObject.fold(json) { obj =>
        val values = tmap.toMap(identity[javaApi.Value]).asScala
        Json.fromFields(obj.toList.map { case (k, j) =>
          k -> values.get(k).fold(j)(trim(_, j))
        })
      }
    case gmap: javaApi.DamlGenMap =>
      // Array of [key, value] pairs. Matched by position.
      json.asArray.fold(json) { arr =>
        val entries = gmap.stream().iterator().asScala.toList
        if (entries.sizeIs == arr.size)
          Json.fromValues(entries.zip(arr).map { case (e, entryJson) =>
            entryJson.asArray match {
              case Some(Vector(k, v)) => Json.arr(trim(e.getKey, k), trim(e.getValue, v))
              case _ => entryJson
            }
          })
        else json
      }
    case opt: javaApi.DamlOptional =>
      opt.getValue.toScala.fold(json) {
        // Optionals nested in optionals are encoded as arrays: Some(None) = [], Some(Some(x)) = [x]
        case inner: javaApi.DamlOptional =>
          json.asArray match {
            case Some(Vector(x)) => Json.arr(trim(inner, x))
            case _ => json
          }
        case inner => trim(inner, json)
      }
    case _ => json
  }

  // Fields are matched by position, the codegen encoder writes fields in declaration order.
  private def trimRecord(record: javaApi.DamlRecord, json: Json): Json =
    json.asObject.fold(json) { obj =>
      val fieldValues = record.getFields.asScala.toList.map(_.getValue)
      val trimmed = obj.toList.zipWithIndex.map { case ((name, j), i) =>
        name -> fieldValues.lift(i).fold(j)(trim(_, j))
      }
      Json.fromFields(trimmed.reverse.dropWhile { case (_, j) => j.isNull }.reverse)
    }
}

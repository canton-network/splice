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
  *
  * Pure and total. If the JSON does not have the shape implied by the value, which should never happen
  * as the JSON is encoded from the value, nothing is trimmed and a `Left` describing the first mismatch
  * is returned. The JSON encoder is better tested than this code, so a partially trimmed result
  * would be more likely to do harm than good.
  * The value may have fewer record fields than the JSON, because the JSON is encoded by the latest codegen;
  * extra trailing `null` fields are expected.
  */
object DropTrailingNulls {

  def trim(value: javaApi.Value, json: Json): Either[String, Json] = go(value, json, "$")

  private def mismatch(path: String, msg: String): Left[String, Nothing] = Left(s"$path: $msg")

  private def sequence[A](xs: Iterable[Either[String, A]]): Either[String, List[A]] =
    xs.foldRight[Either[String, List[A]]](Right(Nil)) { (x, acc) =>
      for { a <- x; as <- acc } yield a :: as
    }

  private def go(value: javaApi.Value, json: Json, path: String): Either[String, Json] =
    value match {
      case record: javaApi.DamlRecord => trimRecord(record, json, path)
      case variant: javaApi.Variant =>
        // `{"tag": ..., "value": ...}`: the payload is trimmed, but the `value` field is never dropped
        for {
          obj <- json.asObject.toRight(s"$path: expected a JSON object for a variant")
          payload <- obj("value").toRight(s"$path: variant JSON has no `value` field")
          trimmed <- go(variant.getValue, payload, s"$path.value")
        } yield Json.fromJsonObject(obj.add("value", trimmed))
      case list: javaApi.DamlList =>
        for {
          arr <- json.asArray.toRight(s"$path: expected a JSON array for a list")
          elems = list.toList(identity[javaApi.Value]).asScala.toList
          _ <- Either.cond(
            elems.sizeIs == arr.size,
            (),
            s"$path: list has ${elems.size} elements but JSON array has ${arr.size}",
          )
          trimmed <- sequence(elems.zip(arr).zipWithIndex.map { case ((v, j), i) =>
            go(v, j, s"$path[$i]")
          })
        } yield Json.fromValues(trimmed)
      case tmap: javaApi.DamlTextMap =>
        for {
          obj <- json.asObject.toRight(s"$path: expected a JSON object for a text map")
          values = tmap.toMap(identity[javaApi.Value]).asScala
          _ <- Either.cond(
            values.size == obj.size,
            (),
            s"$path: text map has ${values.size} entries but JSON object has ${obj.size}",
          )
          trimmed <- sequence(obj.toList.map { case (k, j) =>
            values
              .get(k)
              .toRight(s"$path.$k: key not found in text map")
              .flatMap(go(_, j, s"$path.$k"))
              .map(k -> _)
          })
        } yield Json.fromFields(trimmed)
      case gmap: javaApi.DamlGenMap =>
        // Array of [key, value] pairs. Matched by position.
        for {
          arr <- json.asArray.toRight(s"$path: expected a JSON array for a gen map")
          entries = gmap.stream().iterator().asScala.toList
          _ <- Either.cond(
            entries.sizeIs == arr.size,
            (),
            s"$path: gen map has ${entries.size} entries but JSON array has ${arr.size}",
          )
          trimmed <- sequence(entries.zip(arr).zipWithIndex.map { case ((e, entryJson), i) =>
            entryJson.asArray match {
              case Some(Vector(k, v)) =>
                for {
                  tk <- go(e.getKey, k, s"$path[$i][0]")
                  tv <- go(e.getValue, v, s"$path[$i][1]")
                } yield Json.arr(tk, tv)
              case _ => mismatch(s"$path[$i]", "expected a [key, value] pair for a gen map entry")
            }
          })
        } yield Json.fromValues(trimmed)
      case opt: javaApi.DamlOptional =>
        opt.getValue.toScala.fold[Either[String, Json]](
          Either.cond(json.isNull, json, s"$path: expected null for an empty optional")
        ) {
          // Optionals nested in optionals are encoded as arrays: Some(None) = [], Some(Some(x)) = [x]
          case inner: javaApi.DamlOptional =>
            json.asArray match {
              case Some(Vector(x)) => go(inner, x, s"$path[0]").map(Json.arr(_))
              case Some(Vector()) if inner.isEmpty => Right(json)
              case _ => mismatch(path, "expected a JSON array for a nested optional")
            }
          case inner => go(inner, json, path)
        }
      case _ => Right(json)
    }

  // Fields are matched by position, the codegen encoder writes fields in declaration order.
  private def trimRecord(
      record: javaApi.DamlRecord,
      json: Json,
      path: String,
  ): Either[String, Json] =
    for {
      obj <- json.asObject.toRight(s"$path: expected a JSON object for a record")
      fieldValues = record.getFields.asScala.toList.map(_.getValue)
      _ <- Either.cond(
        obj.size >= fieldValues.size,
        (),
        s"$path: record has ${fieldValues.size} fields but JSON object has ${obj.size}",
      )
      fields <- sequence(obj.toList.zipWithIndex.map { case ((name, j), i) =>
        fieldValues.lift(i) match {
          case Some(v) => go(v, j, s"$path.$name").map(name -> _)
          // Fields beyond the value's fields were added by a later daml version, so they must be null
          case None if j.isNull => Right(name -> j)
          case None =>
            mismatch(s"$path.$name", "non-null JSON field without a corresponding record field")
        }
      })
    } yield Json.fromFields(fields.reverse.dropWhile { case (_, j) => j.isNull }.reverse)
}

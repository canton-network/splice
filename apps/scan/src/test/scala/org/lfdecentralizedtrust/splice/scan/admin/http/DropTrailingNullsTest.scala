// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.daml.ledger.javaapi.data as javaApi
import com.digitalasset.canton.BaseTest
import io.circe.Json
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters.*

class DropTrailingNullsTest extends AnyWordSpec with BaseTest {

  "DropTrailingNulls" should {
    def parseJson(s: String): Json =
      io.circe.parser.parse(s).getOrElse(fail(s"Invalid JSON: $s"))
    def rec(vs: javaApi.Value*): javaApi.DamlRecord =
      new javaApi.DamlRecord(vs.map(new javaApi.DamlRecord.Field(_)).asJava)
    def text(s: String): javaApi.Value = new javaApi.Text(s)
    val none: javaApi.Value = javaApi.DamlOptional.EMPTY
    def some(v: javaApi.Value): javaApi.Value = javaApi.DamlOptional.of(v)

    def check(value: javaApi.Value, json: String, expected: String) =
      DropTrailingNulls.trim(value, parseJson(json)) shouldBe parseJson(expected)

    "drop all trailing null record fields" in {
      check(
        rec(text("x"), none, none),
        """{"a": "x", "b": null, "c": null}""",
        """{"a": "x"}""",
      )
      check(rec(none, none), """{"a": null, "b": null}""", """{}""")
    }

    "keep null record fields that are not trailing" in {
      check(
        rec(none, text("x"), none),
        """{"a": null, "b": "x", "c": null}""",
        """{"a": null, "b": "x"}""",
      )
      check(rec(text("x")), """{"a": "x"}""", """{"a": "x"}""")
    }

    "trim nested records" in {
      check(
        rec(rec(text("x"), none), none),
        """{"a": {"x": "x", "y": null}, "b": null}""",
        """{"a": {"x": "x"}}""",
      )
    }

    "trim records inside lists and optionals" in {
      check(
        javaApi.DamlList.of(rec(text("x"), none), rec(none, none)),
        """[{"a": "x", "b": null}, {"a": null, "b": null}]""",
        """[{"a": "x"}, {}]""",
      )
      check(some(rec(text("x"), none)), """{"a": "x", "b": null}""", """{"a": "x"}""")
      check(none, "null", "null")
    }

    "trim records inside nested optionals" in {
      check(some(some(rec(text("x"), none))), """[{"a": "x", "b": null}]""", """[{"a": "x"}]""")
      check(some(none), "[]", "[]")
    }

    "not drop the value of variants, but trim records inside" in {
      check(
        new javaApi.Variant("Foo", none),
        """{"tag": "Foo", "value": null}""",
        """{"tag": "Foo", "value": null}""",
      )
      check(
        new javaApi.Variant("Foo", rec(text("x"), none)),
        """{"tag": "Foo", "value": {"a": "x", "b": null}}""",
        """{"tag": "Foo", "value": {"a": "x"}}""",
      )
    }

    "not drop null values of text maps, but trim records inside" in {
      check(
        javaApi.DamlTextMap.of(Map("k1" -> none, "k2" -> rec(text("x"), none)).asJava),
        """{"k1": null, "k2": {"a": "x", "b": null}}""",
        """{"k1": null, "k2": {"a": "x"}}""",
      )
    }

    "trim records inside gen maps" in {
      check(
        javaApi.DamlGenMap.of(
          Map[javaApi.Value, javaApi.Value](text("k") -> rec(text("x"), none)).asJava
        ),
        """[["k", {"a": "x", "b": null}]]""",
        """[["k", {"a": "x"}]]""",
      )
    }

    "leave JSON that does not match the value unchanged" in {
      check(rec(text("x"), none), """["a", null]""", """["a", null]""")
      check(text("x"), """"x"""", """"x"""")
    }
  }
}

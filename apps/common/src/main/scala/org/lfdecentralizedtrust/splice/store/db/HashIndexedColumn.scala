// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import io.circe.Json
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder

/** An index column that holds variable-length text or JSON, and whose index covers a hash of the value instead of
  * the value itself (see `V086__hash_key_functions.sql` and `SqlIndexInitializer`).
  * This keeps the index entries small, whatever the length of the value.
  *
  * Do not compare such a column directly (`column = value`): the query would return the right rows,
  * but could not use the hash index. Use `equalTo` instead, which filters on the hash to use the index,
  * and rechecks the raw value. This also works while the indexes are being migrated, as the database
  * can use either the old index on the raw value or the new one on the hash.
  */
sealed trait HashIndexedColumn {
  def name: String
}

/** A text column indexed by `splice_text_key(column)`. */
final case class HashIndexedTextColumn(name: String) extends HashIndexedColumn {

  /** Filters for rows where this column equals `value`. */
  def equalTo(value: String): SQLActionBuilder =
    sql"(splice_text_key(#$name) = splice_text_key($value) and #$name = $value)"
}

/** A jsonb column indexed by `splice_jsonb_key(column)`, which is consistent with jsonb equality,
  * e.g. numbers that are equal but written with a different scale have the same key.
  */
final case class HashIndexedJsonbColumn(name: String) extends HashIndexedColumn {

  /** Filters for rows where this column equals `value`. */
  def equalTo(value: Json): SQLActionBuilder = {
    val json = value.noSpaces
    sql"""(splice_jsonb_key(#$name) = splice_jsonb_key(cast($json as jsonb))
          and #$name = cast($json as jsonb))"""
  }
}

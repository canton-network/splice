// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import slick.dbio.Effect
import slick.jdbc.GetResult
import slick.jdbc.canton.SQLActionBuilder
import slick.sql.SqlStreamingAction

/** Syntax for running data-modifying statements that also return rows, such as
  * PostgreSQL's `insert ... returning`, `insert ... on conflict do nothing returning`,
  * and `delete ... returning`.
  */
object AsUpdateReturning {

  implicit class SQLActionBuilderAsUpdateReturning(private val builder: SQLActionBuilder)
      extends AnyVal {

    /** Run this statement as one that both writes and reads back rows.
      *
      * Neither of the combinators that come with [[slick.jdbc.canton.SQLActionBuilder]] fits
      * `... returning ...` statements:
      *
      *   - `asUpdate` decodes the result with `GetResult.GetUpdateValue`, i.e. it yields the JDBC
      *     update count as a single `Int` and throws away the result set. That makes it impossible
      *     to observe the returned columns, which is the whole point of a `returning` clause; we
      *     need them for generated keys (`update_history_transactions.row_id`), for the
      *     `(contract_id, event_number)` pairs that tell us which of a batch of ACS inserts were
      *     *not* skipped by `on conflict do nothing`, and for the `(first_row_id, last_row_id)`
      *     ranges of the `acs_snapshot` rows we just deleted.
      *   - `as[R]` does decode the result set, but types the action as `Effect.Read` alone. Since
      *     Canton's `DbStorage` overloads dispatch on these phantom effect types (`query` demands
      *     `Effect.Read with Effect.Transactional`, whereas `update`/`queryAndUpdate` demand
      *     `Effect.Write`/`Effect.All`), a mutating statement typed as a pure read type-checks in
      *     `storage.query`, where it would be run with read-only semantics: possibly against a
      *     read-only replica, and freely retried as if it were idempotent.
      *
      * So this simply relabels the read action as also writing, which - because slick's `DBIOAction`
      * is contravariant in its effect parameter - is a pure widening of `as[R]`, and forces callers
      * to go through `storage.update`/`storage.queryAndUpdate`.
      */
    def asUpdateReturning[R](implicit
        rconv: GetResult[R]
    ): SqlStreamingAction[Vector[R], R, Effect.Read & Effect.Write] =
      builder.as[R]
  }
}


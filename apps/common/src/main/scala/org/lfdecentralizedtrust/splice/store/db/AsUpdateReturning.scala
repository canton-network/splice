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
private[store] object AsUpdateReturning {

  implicit class `SQLActionBuilder asUpdateReturning`(private val builder: SQLActionBuilder)
      extends AnyVal {

    /** Run this statement as one that both writes and reads back rows.
      *
      * Neither of the combinators that come with [[slick.jdbc.canton.SQLActionBuilder]]
      * fits `... returning ...` statements:
      *
      *   - `asUpdate` yields the JDBC update count as a single `Int` and throws
      *     away the result set.
      *   - `as[R]` does decode the result set, but types the action as
      *     `Effect.Read` alone, which would allow its usage with unsafe
      *     combinators.
      *
      * So this simply relabels the read action as also writing by widening the
      * result of `as[R]`.
      */
    def asUpdateReturning[R](implicit
        rconv: GetResult[R]
    ): SqlStreamingAction[Vector[R], R, Effect.Read & Effect.Write] =
      builder.as[R]
  }
}

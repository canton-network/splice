// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.resource.DbStorage.SQLActionBuilderChain
import slick.jdbc.SetParameter
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder

import scala.reflect.ClassTag

trait Queries extends JdbcTypes {

  /** Constructions like `seq.mkString("(", ",", ")")` are dangerous because they can lead to SQL injection.
    * Prefer using this instead.
    */
  protected def sqlCommaSeparated(
      seq: Iterable[SQLActionBuilder]
  ): SQLActionBuilderChain = {
    seq
      .map(SQLActionBuilderChain(_))
      .reduceOption { (acc, next) =>
        acc ++ sql"," ++ next
      }
      .getOrElse(SQLActionBuilderChain(sql""))
  }

  /*
   * TODO(#3900) move to use toInClause when canton fork has it: https://github.com/canton-network/splice/issues/3900
   */
  protected def inClause[V: ClassTag](
      field: String,
      seq: Iterable[V],
  )(implicit
      arraySetParameter: SetParameter[Array[V]]
  ): SQLActionBuilder =
    sql" #$field = ANY(${seq.toArray[V]})"

  protected def notInClause[V: ClassTag](
      field: String,
      seq: Iterable[V],
  )(implicit
      arraySetParameter: SetParameter[Array[V]]
  ): SQLActionBuilder =
    sql" NOT (#$field = ANY(${seq.toArray[V]}))"

}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.DbConfig
import com.typesafe.config.ConfigValueFactory

import scala.concurrent.duration.*

object SpliceDbConfig {

  /** Default interval for PostgreSQL's `client_connection_check_interval` setting.
    * When set on a connection, the server periodically checks whether the TCP client
    * is still alive and cancels running queries if it is not. This prevents orphaned
    * queries from consuming resources after a Splice app pod dies (e.g., OOM kill).
    */
  val defaultClientConnectionCheckInterval: FiniteDuration = 5.seconds

  def withConfiguredPostgresConnectionSettings(
      dbConfig: DbConfig,
      postgresConfig: SplicePostgresConfig,
  ): DbConfig =
    withClientConnectionCheckInterval(
      dbConfig,
      postgresConfig.clientConnectionCheckInterval.toInternal.toScala,
    )

  /** Return a copy of `dbConfig` whose underlying Slick/HikariCP `connectionInitSql`
    * includes a `SET client_connection_check_interval` statement. For non-Postgres
    * configs the input is returned unchanged.
    *
    * If the config already contains a user-provided `connectionInitSql`, the SET
    * statement is prepended so both statements execute on every new pooled connection.
    */
  def withClientConnectionCheckInterval(
      dbConfig: DbConfig,
      interval: FiniteDuration = defaultClientConnectionCheckInterval,
  ): DbConfig =
    dbConfig match {
      case pg: DbConfig.Postgres if interval.toMillis > 0 =>
        val setSql = s"SET client_connection_check_interval TO '${interval.toMillis} ms'"
        val existing =
          if (pg.config.hasPath("connectionInitSql")) pg.config.getString("connectionInitSql")
          else ""
        val combined =
          if (existing.isEmpty) setSql else s"$setSql; $existing"
        pg.modify(
          config = pg.config.withValue("connectionInitSql", ConfigValueFactory.fromAnyRef(combined))
        )
      case other => other
    }
}

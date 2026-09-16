// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.DbConfig
import com.typesafe.config.ConfigValueFactory

import scala.concurrent.duration.*

object SpliceDbConfig {

  def withConfiguredPostgresConnectionSettings(
      dbConfig: DbConfig,
      postgresConfig: SplicePostgresConfig,
  ): DbConfig =
    withClientConnectionCheckInterval(
      dbConfig,
      postgresConfig.clientConnectionCheckInterval.toInternal.toScala,
    )

  /** Return a copy of `dbConfig` whose underlying Slick/HikariCP `connectionInitSql`
    * includes a `SET client_connection_check_interval` statement to prevent orphaned
    * queries from consuming resources after a Splice app pod dies.
    *
    * If `connectionInitSql` is already present, prepend the setting so both
    * statements execute on every new pooled connection.
    *
    * @see https://www.postgresql.org/docs/18/runtime-config-connection.html#GUC-CLIENT-CONNECTION-CHECK-INTERVAL
    */
  private[config] def withClientConnectionCheckInterval(
      dbConfig: DbConfig,
      interval: FiniteDuration,
  ): DbConfig =
    dbConfig match {
      case pg: DbConfig.Postgres if interval.toMillis > 0 =>
        val setSql = s"SET client_connection_check_interval TO '${interval.toMillis} ms'"
        val combined =
          if (pg.config.hasPath("connectionInitSql"))
            s"$setSql; ${pg.config.getString("connectionInitSql")}"
          else setSql
        pg.modify(
          config = pg.config.withValue("connectionInitSql", ConfigValueFactory.fromAnyRef(combined))
        )
      case other => other
    }
}

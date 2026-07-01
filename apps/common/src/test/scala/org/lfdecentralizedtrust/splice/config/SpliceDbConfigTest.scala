// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.DbConfig
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.*

class SpliceDbConfigTest extends AnyWordSpec with BaseTest {
  import SpliceDbConfig.{
    withClientConnectionCheckInterval,
    withConfiguredPostgresConnectionSettings,
  }

  private def pgConfig(extraEntries: String = "") =
    DbConfig.Postgres(
      ConfigFactory.parseString(s"""
        |{
        |  url = "jdbc:postgresql://localhost:5432/testdb"
        |  user = "user"
        |  password = "pass"
        |  driver = org.postgresql.Driver
        |  $extraEntries
        |}
      """.stripMargin)
    )

  private def h2Config =
    DbConfig.H2(
      ConfigFactory.parseString(s"""
        |{
        |  url = "jdbc:h2:mem:testdb;MODE=PostgreSQL"
        |  user = "user"
        |  password = "pass"
        |}
      """.stripMargin)
    )

  "SpliceDbConfig.withClientConnectionCheckInterval" should {
    "add connectionInitSql to a Postgres config" in {
      val result = withClientConnectionCheckInterval(pgConfig(), 6.seconds)
      result match {
        case pg: DbConfig.Postgres =>
          pg.config.getString("connectionInitSql") shouldBe
            "SET client_connection_check_interval TO '6000 ms'"
        case other => fail(s"Expected Postgres, got $other")
      }
    }

    "prepend to existing connectionInitSql" in {
      val result = withClientConnectionCheckInterval(
        pgConfig("""connectionInitSql = "SET statement_timeout TO '30s'" """),
        7.seconds,
      )
      result match {
        case pg: DbConfig.Postgres =>
          pg.config.getString("connectionInitSql") shouldBe
            "SET client_connection_check_interval TO '7000 ms'; SET statement_timeout TO '30s'"
        case other => fail(s"Expected Postgres, got $other")
      }
    }

    "use the shared Splice Postgres config" in {
      val result = withConfiguredPostgresConnectionSettings(
        pgConfig(),
        SplicePostgresConfig(
          clientConnectionCheckInterval = NonNegativeFiniteDuration.ofSeconds(10)
        ),
      )
      result match {
        case pg: DbConfig.Postgres =>
          pg.config.getString("connectionInitSql") shouldBe
            "SET client_connection_check_interval TO '10000 ms'"
        case other => fail(s"Expected Postgres, got $other")
      }
    }

    "return H2 config unchanged" in {
      val h2 = h2Config
      val result = withClientConnectionCheckInterval(h2, 5.seconds)
      result shouldBe h2
    }

    "return Postgres config unchanged when interval is zero" in {
      val pg = pgConfig()
      val result = SpliceDbConfig.withClientConnectionCheckInterval(pg, interval = 0.seconds)
      result shouldBe pg
    }
  }
}

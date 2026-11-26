// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.{DbStorageSetup, PostgresCISetup}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.AcsSnapshotDDL
import org.lfdecentralizedtrust.splice.store.StoreTestBase
import org.scalatest.Assertion
import slick.dbio.DBIO
import slick.jdbc.GetResult
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.Future

/** Compares the database schema that the migrations create with the checked-in snapshots
  * [[DbSchemaSnapshotTest.tablesFile]] (tables, columns and constraints) and
  * [[DbSchemaSnapshotTest.indexesFile]] (indexes, with the types of their keys).
  *
  * The snapshots make schema changes visible in reviews, and show the resulting schema without folding
  * all migrations by hand, e.g. to check which index serves a query.
  *
  * Besides the migrations, the snapshots include the tables and indexes that Scan creates at runtime
  * for each v1 ACS snapshot, using an example snapshot. This test lives in the Scan module because that
  * DDL is part of Scan's [[AcsSnapshotDDL]].
  * Partitions of the update history tables are not included: they have the columns, constraints and indexes
  * of their parent table.
  */
class DbSchemaSnapshotTest extends StoreTestBase with SplicePostgresTest {
  import DbSchemaSnapshotTest.*

  // Other suites share the CI database and may leave tables or indexes of their own behind.
  // Use a database that only this suite migrates, so that the snapshots only reflect the migrations.
  // (With test containers, every suite gets its own database anyway.)
  override protected def createSetup(): DbStorageSetup =
    if (DbStorageSetup.useTestContainer()) super.createSetup()
    else
      new PostgresCISetup(
        migrationMode,
        mkDbConfig,
        Some("splice_schema_snapshot"),
        loggerFactory,
      ).initialized()

  private lazy val exampleAcsSnapshotCreated: Future[Unit] =
    storage.underlying
      .queryAndUpdate(createExampleAcsSnapshotTables, "createExampleAcsSnapshotTables")
      .failOnShutdown

  "The database schema" should {
    "match the checked-in snapshot of tables" in {
      for {
        _ <- exampleAcsSnapshotCreated
        columns <- storage.underlying.query(columnsQuery, "listColumns").failOnShutdown
        constraints <- storage.underlying
          .query(constraintsQuery, "listConstraints")
          .failOnShutdown
      } yield checkSnapshot(tablesFile, renderTables(columns, constraints))
    }

    "match the checked-in snapshot of indexes" in {
      for {
        _ <- exampleAcsSnapshotCreated
        keys <- storage.underlying.query(indexKeysQuery, "listIndexKeys").failOnShutdown
      } yield checkSnapshot(indexesFile, renderIndexes(keys))
    }
  }

  private def checkSnapshot(file: Path, actual: String): Assertion =
    if (sys.env.contains(UpdateEnvVar)) {
      Files.createDirectories(file.getParent)
      Files.write(file, actual.getBytes(StandardCharsets.UTF_8))
      logger.info(s"Updated $file")
      succeed
    } else {
      val expected = if (Files.exists(file)) Files.readString(file) else ""
      if (actual != expected) {
        fail(
          s"""The database schema differs from $file.
             |If the change is intended, update the snapshots with:
             |  $UpdateCommand
             |Changes:
             |${describeChanges(expected, actual)}""".stripMargin
        )
      } else succeed
    }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = FutureUnlessShutdown.unit
}

object DbSchemaSnapshotTest {

  private val UpdateEnvVar = "UPDATE_SCHEMA_SNAPSHOT"

  private val UpdateCommand =
    s"""$UpdateEnvVar=1 sbt "apps-scan/testOnly ${classOf[DbSchemaSnapshotTest].getName}""""

  private def snapshotPath(name: String): Path = {
    val root = sys.env.getOrElse(
      "SPLICE_ROOT",
      sys.error("SPLICE_ROOT is not set, run the test in the direnv environment of the repository"),
    )
    Paths.get(root, "apps/common/src/test/resources/db", name)
  }

  private def tablesFile: Path = snapshotPath("schema-tables.txt")
  private def indexesFile: Path = snapshotPath("schema-indexes.txt")

  /** Describes the changes from `expected` to `actual` per block (the header, or one table),
    * as the title of each changed block followed by its removed (`-`) and added (`+`) lines.
    */
  private def describeChanges(expected: String, actual: String): String = {
    def blocks(text: String): Map[String, IndexedSeq[String]] =
      text
        .split("\n\n")
        .toSeq
        .map(_.linesIterator.toIndexedSeq)
        .filter(_.nonEmpty)
        .map { lines =>
          val title = lines.headOption.fold("")(_.takeWhile(_ != ' '))
          (if (title == "#") "(header)" else title) -> lines
        }
        .toMap
    val expectedBlocks = blocks(expected)
    val actualBlocks = blocks(actual)
    (expectedBlocks.keySet ++ actualBlocks.keySet).toSeq.sorted
      .flatMap { title =>
        val expectedLines = expectedBlocks.getOrElse(title, IndexedSeq.empty)
        val actualLines = actualBlocks.getOrElse(title, IndexedSeq.empty)
        Option.when(expectedLines != actualLines)(
          (s"$title:" +: diffLines(expectedLines, actualLines)).mkString("\n")
        )
      }
      .mkString("\n\n")
  }

  /** A line diff based on the longest common subsequence, so that moved lines show up as removed and added. */
  private def diffLines(expected: IndexedSeq[String], actual: IndexedSeq[String]): Seq[String] = {
    // lcs(i)(j) is the length of the longest common subsequence of expected.drop(i) and actual.drop(j).
    val lcs = Array.ofDim[Int](expected.size + 1, actual.size + 1)
    for (i <- expected.indices.reverse; j <- actual.indices.reverse)
      lcs(i)(j) =
        if (expected(i) == actual(j)) lcs(i + 1)(j + 1) + 1
        else math.max(lcs(i + 1)(j), lcs(i)(j + 1))
    def go(i: Int, j: Int): List[String] =
      if (i < expected.size && j < actual.size && expected(i) == actual(j)) go(i + 1, j + 1)
      else if (i < expected.size && (j == actual.size || lcs(i + 1)(j) >= lcs(i)(j + 1)))
        s"- ${expected(i)}" :: go(i + 1, j)
      else if (j < actual.size) s"+ ${actual(j)}" :: go(i, j + 1)
      else Nil
    go(0, 0)
  }

  // The ids of the example ACS snapshot. The tables and indexes of a snapshot are named after them;
  // the snapshot files show them as placeholders.
  private val ExampleHistoryId = 12345L
  private val ExampleSnapshotRecordTime = CantonTimestamp.ofEpochMilli(67890L)
  private val ExampleSnapshotIds = s"${ExampleHistoryId}_${ExampleSnapshotRecordTime.toEpochMilli}"
  private val ExampleSnapshotIdsPlaceholder = "<history_id>_<record_time_millis>"

  // Creates the tables and indexes of a v1 ACS snapshot the same way as Scan does at runtime:
  // the tables when saving the snapshot, the indexes in AcsSnapshotIndexTrigger.
  private val createExampleAcsSnapshotTables = {
    val stakeholdersTableName =
      AcsSnapshotDDL.stakeholdersTableName(ExampleHistoryId, ExampleSnapshotRecordTime)
    DBIO.seq(
      AcsSnapshotDDL.createTablesAction(
        AcsSnapshotDDL.createsTableName(ExampleHistoryId, ExampleSnapshotRecordTime),
        stakeholdersTableName,
      ),
      AcsSnapshotDDL.stakeholderIndexAction(
        stakeholdersTableName,
        ExampleHistoryId,
        ExampleSnapshotRecordTime,
      ),
      AcsSnapshotDDL.stakeholderTemplateIdIndexAction(
        stakeholdersTableName,
        ExampleHistoryId,
        ExampleSnapshotRecordTime,
      ),
    )
  }

  // The tables to include: all tables of the current schema, except partitions (which are created at runtime
  // and have the columns, constraints and indexes of their parent) and Flyway's own table.
  private val tableFilter = """
    ns.nspname = current_schema()
    and c.relkind in ('r', 'p')
    and not c.relispartition
    and c.relname <> 'flyway_schema_history'
  """

  /** One column of a table. */
  private final case class Column(
      tableName: String,
      partitionKey: Option[String],
      position: Int,
      name: String,
      columnType: String,
      nonDefaultCollation: Option[String],
      notNull: Boolean,
      default: Option[String],
      identity: String,
      generated: String,
  )

  private implicit val getResultColumn: GetResult[Column] = GetResult { r =>
    Column(r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<)
  }

  private val columnsQuery = sql"""
    select c.relname,
           case when c.relkind = 'p' then pg_get_partkeydef(c.oid) end,
           a.attnum::int,
           a.attname,
           format_type(a.atttypid, a.atttypmod),
           case when a.attcollation <> 0 and a.attcollation <> t.typcollation then co.collname end,
           a.attnotnull,
           pg_get_expr(d.adbin, d.adrelid, true),
           a.attidentity::text,
           a.attgenerated::text
    from pg_class c
    join pg_namespace ns on ns.oid = c.relnamespace
    join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
    join pg_type t on t.oid = a.atttypid
    left join pg_attrdef d on d.adrelid = c.oid and d.adnum = a.attnum
    left join pg_collation co on co.oid = a.attcollation
    where #$tableFilter
  """.as[Column]

  /** A constraint of a table, other than not null (which is shown on the column). */
  private final case class Constraint(tableName: String, name: String, definition: String)

  private implicit val getResultConstraint: GetResult[Constraint] = GetResult { r =>
    Constraint(r.<<, r.<<, r.<<)
  }

  private val constraintsQuery = sql"""
    select c.relname, con.conname, pg_get_constraintdef(con.oid, true)
    from pg_constraint con
    join pg_class c on c.oid = con.conrelid
    join pg_namespace ns on ns.oid = c.relnamespace
    where #$tableFilter
      and con.contype in ('p', 'u', 'f', 'c', 'x')
  """.as[Constraint]

  /** One key (or included column) of an index. */
  private final case class IndexKey(
      tableName: String,
      indexName: String,
      isPrimary: Boolean,
      isUnique: Boolean,
      nullsNotDistinct: Boolean,
      isValid: Boolean,
      method: String,
      predicate: Option[String],
      position: Int,
      isIncluded: Boolean,
      definition: String,
      keyType: String,
      nonDefaultOpClass: Option[String],
      options: Option[Int],
  )

  private implicit val getResultIndexKey: GetResult[IndexKey] = GetResult { r =>
    IndexKey(r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<, r.<<)
  }

  // Lists all keys and included columns of all indexes of the included tables.
  // The type of a key is the type stored in the index, which also covers expression keys.
  private val indexKeysQuery = sql"""
    select c.relname,
           ic.relname,
           i.indisprimary,
           i.indisunique,
           i.indnullsnotdistinct,
           i.indisvalid,
           am.amname,
           pg_get_expr(i.indpred, i.indrelid, true),
           k.n,
           k.n > i.indnkeyatts,
           pg_get_indexdef(i.indexrelid, k.n, true),
           format_type(a.atttypid, a.atttypmod),
           case when k.n <= i.indnkeyatts and not oc.opcdefault then oc.opcname end,
           case when k.n <= i.indnkeyatts then i.indoption[k.n - 1]::int end
    from pg_index i
    join pg_class ic on ic.oid = i.indexrelid
    join pg_class c on c.oid = i.indrelid
    join pg_namespace ns on ns.oid = c.relnamespace
    join pg_am am on am.oid = ic.relam
    cross join lateral generate_series(1, i.indnatts) as k(n)
    join pg_attribute a on a.attrelid = i.indexrelid and a.attnum = k.n
    left join pg_opclass oc on oc.oid = i.indclass[k.n - 1]
    where #$tableFilter
  """.as[IndexKey]

  /** Renders a snapshot file: the header, then one block per table, sorted by table name. */
  private def renderFile(tables: Seq[(String, Seq[String])]): String = {
    val header =
      s"""# Generated by ${classOf[DbSchemaSnapshotTest].getName}. Do not edit by hand.
         |# Regenerate it with: $UpdateCommand
         |""".stripMargin
    val body = tables
      .sortBy(_._1)
      .map { case (table, lines) => (table +: lines.map("  " + _)).mkString("\n") }
      .mkString("\n\n")
    header + "\n" + body.replace(ExampleSnapshotIds, ExampleSnapshotIdsPlaceholder) + "\n"
  }

  private def renderColumn(column: Column): String = {
    val identity = column.identity match {
      case "a" => Some("generated always as identity")
      case "d" => Some("generated by default as identity")
      case _ => None
    }
    val defaultOrGenerated = column.default.map(expr =>
      if (column.generated == "s") s"generated always as ($expr) stored"
      else if (column.generated == "v") s"generated always as ($expr) virtual"
      else s"default $expr"
    )
    (Seq(column.name, column.columnType) ++
      column.nonDefaultCollation.map(c => s"collate \"$c\"") ++
      Option.when(column.notNull)("not null") ++
      identity ++
      defaultOrGenerated).mkString(" ")
  }

  /** One block per table: the columns in table order, then the constraints sorted by name. */
  private def renderTables(columns: Seq[Column], constraints: Seq[Constraint]): String = {
    val constraintsByTable = constraints.groupBy(_.tableName)
    renderFile(
      columns.groupBy(_.tableName).toSeq.map { case (tableName, tableColumns) =>
        val sortedColumns = tableColumns.sortBy(_.position)
        val title = (tableName +: sortedColumns.headOption
          .flatMap(_.partitionKey)
          .toList
          .map(key => s"partitioned by $key")).mkString(" ")
        val constraintLines = constraintsByTable
          .getOrElse(tableName, Seq.empty)
          .sortBy(_.name)
          .map(c => s"constraint ${c.name} ${c.definition}")
        title -> (sortedColumns.map(renderColumn) ++ constraintLines)
      }
    )
  }

  private def renderKey(key: IndexKey): String = {
    val desc = key.options.exists(o => (o & 1) != 0)
    val nullsFirst = key.options.exists(o => (o & 2) != 0)
    val order = (desc, nullsFirst) match {
      case (true, true) => Seq("desc")
      case (true, false) => Seq("desc", "nulls last")
      case (false, true) => Seq("nulls first")
      case (false, false) => Seq.empty
    }
    (Seq(key.definition, key.keyType) ++ key.nonDefaultOpClass ++ order).mkString(" ")
  }

  private def renderIndex(indexName: String, keys: Seq[IndexKey]): String = {
    val sorted = keys.sortBy(_.position)
    // All keys of an index share the per-index fields.
    val index = sorted.headOption.getOrElse(sys.error(s"Index $indexName has no keys"))
    val (included, keyColumns) = sorted.partition(_.isIncluded)
    Seq(
      if (index.isPrimary) Some("primary key")
      else if (index.isUnique) Some("unique")
      else None,
      Some(indexName),
      Option.when(index.method != "btree")(s"using ${index.method}"),
      Some(keyColumns.map(renderKey).mkString("(", ", ", ")")),
      Option.when(included.nonEmpty)(included.map(renderKey).mkString("include (", ", ", ")")),
      Option.when(index.nullsNotDistinct)("nulls not distinct"),
      index.predicate.map(p => s"where $p"),
      Option.when(!index.isValid)("invalid"),
    ).flatten.mkString(" ")
  }

  /** One block per table: the indexes sorted by name. */
  private def renderIndexes(keys: Seq[IndexKey]): String =
    renderFile(
      keys.groupBy(_.tableName).toSeq.map { case (tableName, tableKeys) =>
        tableName -> tableKeys
          .groupBy(_.indexName)
          .toSeq
          .sortBy(_._1)
          .map { case (indexName, indexKeys) => renderIndex(indexName, indexKeys) }
      }
    )
}

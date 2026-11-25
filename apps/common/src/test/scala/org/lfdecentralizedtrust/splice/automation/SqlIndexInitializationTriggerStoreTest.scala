package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.noop.NoOpMetricsFactory
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.{StoreErrors, StoreTestBase}
import org.lfdecentralizedtrust.splice.store.db.AdvisoryLocksTestHelper
import com.digitalasset.canton.concurrent.{FutureSupervisor, Threading}
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.{FutureHelpers, HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.store.db.{
  AcsJdbcTypes,
  AcsTables,
  AdvisoryLocks,
  SplicePostgresTest,
  SqlIndexInitializer,
}
import org.lfdecentralizedtrust.splice.store.db.SqlIndexInitializer.{IndexAction, IndexStatus}
import org.slf4j.event.Level
import slick.dbio.DBIOAction
import slick.jdbc.{GetResult, PositionedResult}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{Future, Promise}

class SqlIndexInitializationTriggerStoreTest
    extends StoreTestBase
    with HasExecutionContext
    with StoreErrors
    with HasActorSystem
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables
    with FutureHelpers
    with AdvisoryLocksTestHelper {

  private val expectedIndexNames = Seq(
    "dso_acs_store_sid_mid_pn_tid_rbio",
    "dso_acs_store_sid_mid_glt_gls",
    "scan_acs_store_sid_mid_glt_gls",
    "scan_txlog_store_sid_effat_en_vot",
    "user_wallet_acs_store_sid_mid_glt_gls",
    "external_party_wallet_acs_store_sid_mid_glt_gls",
  )

  "SqlIndexInitializationTrigger" should {

    "use if not exists concurrently" in {
      SqlIndexInitializer.defaultIndexActions.foreach {
        case IndexAction.Create(_, statement) =>
          // "if not exists" is already enforced by DbStorageIdempotency retrying all statements in tests,
          // but a check here gives better feedback.
          // "concurrently" is not strictly required for the trigger to work, but a non-concurrent index creation
          // would be suspicious.
          statement.getDumpInfo.mainInfo should include("create index concurrently if not exists")
        case IndexAction.Drop(_) => succeed
      }
      succeed
    }

    "run with default settings" in {
      val trigger = SqlIndexInitializationTrigger(
        storage = storage,
        triggerContext = triggerContext,
      )
      for {
        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNames <- listIndexNames()
        _ <- dumpIndexes()
      } yield {
        indexNames should contain allElementsOf expectedIndexNames
        indexNames should not contain "scan_txlog_store_sid_en_vot"
      }
    }

    "create an index" in {
      val trigger = SqlIndexInitializationTrigger(
        storage = storage,
        triggerContext = triggerContext,
        indexActions = List(
          IndexAction.Create(
            "test_index",
            sqlu"create index if not exists test_index on dso_acs_store (contract_id)",
          )
        ),
      )

      for {
        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNames <- listIndexNames()
      } yield {
        indexNames should contain allElementsOf List("test_index")
      }
    }

    "drop index" in {
      val trigger = new SqlIndexInitializationTrigger(
        storage = storage,
        context = triggerContext,
        indexActions = List(
          IndexAction.Drop("test_index")
        ),
      )
      for {
        _ <- storage.underlying
          .update(
            sqlu"create index test_index on dso_acs_store (contract_id)",
            "create test index",
          )
          .failOnShutdown
        indexNamesBefore <- listIndexNames()
        _ = indexNamesBefore should contain("test_index")
        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNamesAfter <- listIndexNames()
        _ = indexNamesAfter should not contain ("test_index")
      } yield succeed
    }

    "do not create an index if it already exists" in {
      val trigger = SqlIndexInitializationTrigger(
        storage = storage,
        triggerContext = triggerContext,
        indexActions = List(
          IndexAction.Create(
            "test_index",
            sqlu"create index if not exists test_index on dso_acs_store (contract_id)",
          )
        ),
      )

      for {
        _ <- storage.underlying
          .update(
            sqlu"create index test_index on dso_acs_store (contract_id)",
            "create test index",
          )
          .failOnShutdown
        tasks <- trigger.retrieveTasks()
        _ = tasks.loneElement shouldBe a[SqlIndexInitializationTrigger.Task.ConfirmActionCompleted]
        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNames <- listIndexNames()
      } yield {
        indexNames should contain allElementsOf List("test_index")
      }
    }

    "do not drop an index if it does not exists" in {
      val trigger = SqlIndexInitializationTrigger(
        storage = storage,
        triggerContext = triggerContext,
        indexActions = List(
          IndexAction.Drop("test_index")
        ),
      )

      for {
        tasks <- trigger.retrieveTasks()
        _ = tasks.loneElement shouldBe a[SqlIndexInitializationTrigger.Task.ConfirmActionCompleted]
        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNames <- listIndexNames()
      } yield {
        indexNames should not contain "test_index"
      }
    }

    "delete invalid index" in {
      val trigger = new SqlIndexInitializationTrigger(
        storage = storage,
        context = triggerContext,
        indexActions = List(
          IndexAction.Create(
            "test_index",
            sqlu"create index concurrently if not exists test_index on active_parties (closed_round)",
          )
        ),
      )
      for {
        _ <- createInvalidTestIndex()
        indexNamesBefore <- listIndexNames()
        _ = indexNamesBefore should contain("test_index")

        tasks <- loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          within = { trigger.retrieveTasks() },
          assertion = { entries =>
            forExactly(1, entries) {
              _.message should include(
                "Index test_index should be created and is invalid, dropping it"
              )
            }
          },
        )
        _ = tasks.loneElement match {
          case SqlIndexInitializationTrigger.Task.ExecuteAction(IndexAction.Drop("test_index")) =>
            succeed
          case other =>
            fail(s"Expected Drop for test_index, got $other")
        }
        outcome <- loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          within = { trigger.runOnceWithTaskOutcome() },
          assertion = { entries =>
            forExactly(1, entries) {
              _.message should include(
                "Index test_index should be created and is invalid, dropping it"
              )
            }
          },
        )
        _ = outcome should matchPattern { case Some(_: TaskSuccess) => }
        indexNamesAfterDrop <- listIndexNames()
        _ = indexNamesAfterDrop should not contain ("test_index")

        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNamesAfter <- listIndexNames()
      } yield {
        indexNamesAfter should contain("test_index")
      }
    }

    "avoid deleting index that is being created" in {
      val trigger = new SqlIndexInitializationTrigger(
        storage = storage,
        context = triggerContext,
        indexActions = List(
          IndexAction.Create(
            "test_index",
            sqlu"create index concurrently if not exists test_index on active_parties (closed_round)",
          )
        ),
      )
      // Too annoying to get this value out of Future.sequence below, so we use a var
      var tasksResult: Option[Seq[SqlIndexInitializationTrigger.Task]] = None
      for {
        _ <- Future.unit
        _ <- storage.underlying
          .update(
            DBIOAction
              .seq(
                sqlu"""
                  insert into active_parties (store_id, party, closed_round)
                  values (1, 'test_party', 1)
                """,
                sqlu"""
                  insert into active_parties (store_id, party, closed_round)
                  values (2, 'test_party2', 1)
                """,
              ),
            "insert test data",
          )
          .failOnShutdown
        _ <- storage.underlying
          .update(
            sqlu"""
              create or replace function slow_function(text) returns text as $$$$
              begin
                  perform pg_sleep(2); -- simulate a long-running operation
                  return $$1;
              end;
              $$$$ language plpgsql immutable;
              """,
            "create slow_function",
          )
          .failOnShutdown

        // This block simulates a trigger checking for tasks while an index is being created.
        // We run the following actions in parallel to achieve this:
        // - Create an index using slow_function(), which takes 4 seconds in total
        //   (2 rows in the table and 2 seconds per function invocation).
        // - Wait 2 seconds, then have the trigger check for tasks.
        _ <- Future.sequence(
          Seq(
            storage.underlying
              .update(
                DBIOAction
                  .seq(
                    // This statement will take 4sec to execute, because slow_function() takes 2 seconds to execute per row,
                    // and there are 2 rows in the table.
                    sqlu"create index concurrently if not exists test_index on active_parties (slow_function(party))",
                    // `set statement_timeout` lasts for the whole session, and we are using connection pools,
                    // so reset it to default to avoid affecting later statements.
                    sqlu"set statement_timeout to default",
                  )
                  .asTry,
                "insert test data",
              )
              .failOnShutdown,
            loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
              within = {
                Threading.sleep(2000)
                trigger.retrieveTasks().map(tasks => tasksResult = Some(tasks))
              },
              assertion = { entries =>
                forExactly(1, entries) {
                  _.message should include(
                    "Index test_index is being built by backend process"
                  )
                }
              },
            ),
          )
        )
      } yield {
        tasksResult.value shouldBe empty
      }
    }

    "skip index DDL quietly while another process holds the advisory lock" in {
      val trigger = SqlIndexInitializationTrigger(
        storage = storage,
        triggerContext = triggerContext,
        indexActions = List(
          IndexAction.Create(
            "test_index",
            sqlu"create index concurrently if not exists test_index on dso_acs_store (contract_id)",
          )
        ),
      )
      val releaseLock = Promise[Unit]()
      val (lockAcquired, lockReleased) =
        holdLock(AdvisoryLocks.withDdlLock, DBIOAction.unit, releaseLock.future)

      for {
        _ <- lockAcquired
        // The outcome is not asserted: the test only checks that the index is not created while
        // the lock is held.
        _ <- trigger.runOnceWithTaskOutcome()
        indexNamesWhileLocked <- listIndexNames()
        _ = indexNamesWhileLocked should not contain "test_index"
        // The contended action is still pending and succeeds once the lock is released
        _ = releaseLock.success(())
        _ <- lockReleased
        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNamesAfter <- listIndexNames()
      } yield indexNamesAfter should contain("test_index")
    }

  }

  "SqlIndexInitializer" should {

    "create and drop the default indexes" in {
      val actions = SqlIndexInitializer.defaultIndexActions
      for {
        _ <- applyAll(actions)
        statuses <- MonadUtil.sequentialTraverse(actions)(action =>
          indexStatus(action.indexName).map(action -> _)
        )
      } yield {
        val wrong = statuses.collect {
          case (IndexAction.Create(name, _), status) if status != IndexStatus.Valid =>
            s"$name should be valid but is $status"
          case (IndexAction.Drop(name), status) if status != IndexStatus.DoesNotExist =>
            s"$name should not exist but is $status"
        }
        wrong shouldBe empty
      }
    }

    "run no DDL when the indexes are already in place" in {
      val actions = SqlIndexInitializer.defaultIndexActions
      for {
        _ <- applyAll(actions)
        _ <- loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
          within = applyAll(actions),
          assertion = { entries =>
            val ddl = entries
              .map(_.message)
              .filter(m => m.startsWith("Creating index") || m.startsWith("Dropping index"))
            ddl shouldBe empty
          },
        )
      } yield succeed
    }

    "fail on an invalid index" in {
      val action = IndexAction.Create(
        "test_index",
        sqlu"create index concurrently if not exists test_index on active_parties (closed_round)",
      )
      for {
        _ <- createInvalidTestIndex()
        error <- loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          within = applyAll(List(action)).failed,
          assertion = { entries =>
            forExactly(1, entries) {
              _.message should include(
                "Index test_index should be created and is invalid, dropping it"
              )
            }
          },
        )
      } yield error.getMessage should include("Index test_index is invalid")
    }
  }

  private def applyAll(actions: List[IndexAction]): Future[Unit] =
    new SqlIndexInitializer(storage, loggerFactory).applyAll(actions).failOnShutdown

  private def indexStatus(indexName: String): Future[IndexStatus] =
    storage.underlying
      .query(SqlIndexInitializer.getIndexStatusAction(indexName), "getIndexStatus")
      .failOnShutdown

  private def createInvalidTestIndex(): Future[Unit] =
    for {
      _ <- storage.underlying
        .update(
          DBIOAction
            .seq(
              sqlu"""
                insert into active_parties (store_id, party, closed_round)
                values (1, 'test_party', 1)
              """,
              sqlu"""
                insert into active_parties (store_id, party, closed_round)
                values (2, 'test_party2', 1)
              """,
            ),
          "insert test data",
        )
        .failOnShutdown
      _ <- storage.underlying
        .update(
          sqlu"""
            create or replace function slow_function(text) returns text as $$$$
            begin
                perform pg_sleep(5); -- simulate a long-running operation
                return $$1;
            end;
            $$$$ language plpgsql immutable;
            """,
          "create slow_function",
        )
        .failOnShutdown
      _ <- storage.underlying
        .update(
          DBIOAction
            .seq(
              sqlu"set statement_timeout to '1s'",
              // This statement will be aborted, because slow_function() takes 5 seconds to execute per row,
              // and the statement timeout is set to 1 second above.
              // 'create index concurrently' internally consists of 3 transactions: one to register the index as invalid,
              // and two table scans to build the index. Aborting the statement will leave the index in an invalid state.
              sqlu"create index concurrently if not exists test_index on active_parties (slow_function(party))",
              // `set statement_timeout` lasts for the whole session, and we are using connection pools,
              // so reset it to default to avoid affecting later statements.
              sqlu"set statement_timeout to default",
            )
            .asTry,
          "insert test data",
        )
        .failOnShutdown
    } yield ()

  private def listIndexNames(): Future[Seq[String]] = {
    storage.underlying
      .query(
        sql"select indexname from pg_indexes where schemaname = 'public'".as[String],
        "listIndexes",
      )
      .failOnShutdown
  }

  // One row in pg_indexes
  private case class IndexesEntry(
      schemaName: String,
      tableName: String,
      indexName: String,
      indexDefinition: String,
  )

  private implicit val GetResultIndexesEntry: GetResult[IndexesEntry] = { (pp: PositionedResult) =>
    IndexesEntry(
      pp.<<,
      pp.<<,
      pp.<<,
      pp.<<,
    )
  }

  // Dumps information about all indexes in the database to the log.
  // Used during development to verify that the indexes are created correctly.
  private def dumpIndexes(): Future[Unit] = {
    storage.underlying
      .query(
        sql"""
      select
        i.schemaname, i.tablename, i.indexname, i.indexdef
      from
        pg_indexes i
    """.as[IndexesEntry],
        "dumpIndexes",
      )
      .map { indexes =>
        logger.info(s"Indexes: ${indexes.mkString("\n  ", "\n  ", "\n")}")
      }
      .failOnShutdown
  }

  private def dropIndexes(indexNames: Seq[String]): FutureUnlessShutdown[Unit] = {
    MonadUtil
      .sequentialTraverse(indexNames) { indexName =>
        storage
          .update(
            sqlu"drop index if exists #${indexName}",
            s"drop index $indexName",
          )
      }
      .map(_ => ())
  }

  private def runTriggerUntilAllTasksDone(trigger: SqlIndexInitializationTrigger): Future[Unit] = {
    trigger.run(paused = false)
    trigger.remainingActionsEmpty.future
  }

  private lazy val triggerContext: TriggerContext = TriggerContext(
    AutomationConfig(),
    clock,
    clock,
    TriggerEnabledSynchronization.Noop,
    RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    loggerFactory,
    NoOpMetricsFactory,
  )

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = for {
    _ <- resetAllAppTables(storage)
    _ <- dropIndexes(
      SqlIndexInitializer.defaultIndexActions.map(_.indexName) ++ Seq("test_index")
    )
    // Need to drop this after dropping the indexes using it.
    _ <- storage.update(sqlu"drop function if exists slow_function", "drop slow_function")
  } yield ()
}

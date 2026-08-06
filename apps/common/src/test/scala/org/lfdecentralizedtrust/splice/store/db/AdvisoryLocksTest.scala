// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import cats.Monad
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.store.StoreTestBase
import slick.dbio.DBIOAction
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{Future, Promise}

class AdvisoryLocksTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables {

  private val testIndexNames: Seq[String] = (1 to 8).map(i => s"test_index_$i")

  "AdvisoryLocks" should {

    "not collide when several concurrent DDL statements run" in {
      // Create several indexes concurrently. Without the advisory lock these would compete, either
      // failing or leaving behind invalid indexes
      for {
        _ <- Future.sequence(testIndexNames.map(createIndexWithDdlLock))
        invalidIndexes <- invalidIndexNames()
        indexNames <- listIndexNames()
      } yield {
        invalidIndexes shouldBe empty
        indexNames should contain allElementsOf testIndexNames
      }
    }

    "release the lock after running an action" in {
      for {
        _ <- createIndexWithDdlLock("test_index_1")
        lockIsFree <- ddlLockIsFree()
      } yield lockIsFree shouldBe true
    }

    "release the lock after an action fails" in {
      for {
        failure <- storage.underlying
          .queryAndUpdate(
            AdvisoryLocks.withDdlLock(sqlu"create index on a_table_that_does_not_exist (bad)"),
            "failing DDL",
          )
          .failOnShutdown
          .failed
        _ = failure shouldBe a[java.sql.SQLException]
        lockIsFree <- ddlLockIsFree()
      } yield lockIsFree shouldBe true
    }

    "fail fast while another session holds the lock" in {
      val releaseLock = Promise[Unit]()
      val (lockAcquired, lockReleased) = holdDdlLock(releaseLock.future)
      for {
        _ <- lockAcquired
        failure <- storage.underlying
          .queryAndUpdate(
            AdvisoryLocks.withDdlLock(
              sqlu"create index concurrently if not exists test_index_1 on update_history_creates (record_time)"
            ),
            "contended DDL",
          )
          .failOnShutdown
          .failed
        indexNamesWhileLocked <- listIndexNames()
        _ = releaseLock.success(())
        _ <- lockReleased
      } yield {
        failure shouldBe AdvisoryLocks.FailedToAcquireAdvisoryLockException(
          AdvisoryLockIds.ddlStatement
        )
        indexNamesWhileLocked should not contain "test_index_1"
      }
    }
  }

  /** Runs `create index concurrently` with the DDL lock, retrying with a small sleep for as long
    * as another session holds the lock.
    */
  private def createIndexWithDdlLock(indexName: String): Future[Unit] =
    Monad[Future].tailRecM(())(_ =>
      storage.underlying
        .queryAndUpdate(
          AdvisoryLocks.withDdlLock(
            sqlu"create index concurrently if not exists #$indexName on update_history_creates (record_time)"
          ),
          s"create $indexName",
        )
        .failOnShutdown
        .map(_ => Right(()))
        .recoverWith { case _: AdvisoryLocks.FailedToAcquireLockException =>
          Future(Threading.sleep(10)).map(_ => Left(()))
        }
    )

  /** Acquires the DDL lock on a separate database session and holds it until `release` completes.
    *
    * Returns a future that completes once the lock is held, and a future that completes once the
    * lock has been released again.
    */
  private def holdDdlLock(release: Future[Unit]): (Future[Unit], Future[Unit]) = {
    val acquired = Promise[Unit]()
    val released = storage.underlying
      .query(
        AdvisoryLocks.withDdlLock(
          DBIOAction
            .successful(())
            .map(_ => acquired.success(()))
            .flatMap(_ => DBIOAction.from(release))
        ),
        "hold DDL lock",
      )
      .failOnShutdown
    // Make sure the test fails rather than hangs if the lock-holding session dies.
    released.failed.foreach(acquired.tryFailure)
    (acquired.future, released)
  }

  /** Whether the DDL lock can be acquired from another session, i.e. nothing is holding it. */
  private def ddlLockIsFree(): Future[Boolean] =
    storage.underlying
      .query(
        (for {
          acquired <- AdvisoryLocks.acquireSessionLock(AdvisoryLockIds.ddlStatement)
          _ <-
            if (acquired) AdvisoryLocks.releaseSessionLock(AdvisoryLockIds.ddlStatement)
            else DBIOAction.successful(false)
        } yield acquired).withPinnedSession,
        "check DDL lock",
      )
      .failOnShutdown

  private def listIndexNames(): Future[Seq[String]] =
    storage.underlying
      .query(
        sql"select indexname from pg_indexes where schemaname = 'public'".as[String],
        "listIndexes",
      )
      .failOnShutdown

  private def invalidIndexNames(): Future[Seq[String]] =
    storage.underlying
      .query(
        sql"""
          select c.relname
          from pg_class c
          join pg_namespace n on n.oid = c.relnamespace
          join pg_index i on i.indexrelid = c.oid
          where n.nspname = current_schema and c.relkind = 'i' and not i.indisvalid
        """.as[String],
        "invalidIndexes",
      )
      .failOnShutdown

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    MonadUtil.sequentialTraverse(testIndexNames) { indexName =>
      storage.update(sqlu"drop index if exists #$indexName", s"drop index $indexName")
    }
}

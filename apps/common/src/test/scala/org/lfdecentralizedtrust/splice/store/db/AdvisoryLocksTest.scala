// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import cats.Monad
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.DbTest
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.store.StoreTestBase
import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{Future, Promise}

trait AdvisoryLocksTestHelper { _: DbTest with StoreTestBase with HasExecutionContext =>

  /** Acquires a lock by calling [[withLock]] and holds it until `release` completes. Returns a
    * future that completes once the lock is held, and a future that completes once the lock has
    * been released.
    */
  final def holdLock(
      withLock: DBIOAction[Unit, NoStream, Effect] => DBIOAction[Unit, NoStream, Effect.All],
      release: Future[Unit],
  ): (Future[Unit], Future[Unit]) = {
    val acquired = Promise[Unit]()
    val released = storage.underlying
      .queryAndUpdate(
        withLock(
          DBIOAction
            .successful(())
            .map(_ => acquired.success(()))
            .flatMap(_ => DBIOAction.from(release))
        ),
        "hold lock",
      )
      .failOnShutdown
    released.failed.foreach(acquired.tryFailure)
    (acquired.future, released)
  }
}

class AdvisoryLocksTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables
    with AdvisoryLocksTestHelper {

  private val testIndexNames: Seq[String] = (1 to 8).map(i => s"test_index_$i")

  "AdvisoryLocks.withSessionLock" should {

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
        lockIsFree <- lockIsFree(AdvisoryLockIds.ddlStatement)
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
        lockIsFree <- lockIsFree(AdvisoryLockIds.ddlStatement)
      } yield lockIsFree shouldBe true
    }

    "fail fast while another session holds the lock" in {
      val releaseLock = Promise[Unit]()
      val (lockAcquired, lockReleased) = holdLock(AdvisoryLocks.withDdlLock, releaseLock.future)
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
        failure shouldBe AdvisoryLocks.FailedToAcquireLockException(
          "session-scoped",
          AdvisoryLockIds.ddlStatement,
        )
        indexNamesWhileLocked should not contain "test_index_1"
      }
    }
  }

  "AdvisoryLocks.withTransactionalLock" should {

    "release the lock when the transaction ends" in {
      for {
        _ <- storage.underlying
          .queryAndUpdate(
            AdvisoryLocks.withTransactionalLock(
              profile,
              AdvisoryLockIds.acsSnapshotDataInsert,
              sqlu"insert into active_parties (store_id, party, closed_round) values (1, 'committed', 1)",
            ),
            "insert under transactional lock",
          )
          .failOnShutdown
        lockIsFree <- lockIsFree(AdvisoryLockIds.acsSnapshotDataInsert)
        committed <- countParties("committed")
      } yield {
        lockIsFree shouldBe true
        committed shouldBe 1
      }
    }

    "roll back the action and release the lock when the action fails" in {
      for {
        failure <- storage.underlying
          .queryAndUpdate(
            AdvisoryLocks.withTransactionalLock(
              profile,
              AdvisoryLockIds.acsSnapshotDataInsert,
              DBIOAction.seq(
                sqlu"insert into active_parties (store_id, party, closed_round) values (2, 'rolled_back', 1)",
                sqlu"insert into a_table_that_does_not_exist values (1)",
              ),
            ),
            "failing action under transactional lock",
          )
          .failOnShutdown
          .failed
        _ = failure shouldBe a[java.sql.SQLException]
        lockIsFree <- lockIsFree(AdvisoryLockIds.acsSnapshotDataInsert)
        rolledBack <- countParties("rolled_back")
      } yield {
        lockIsFree shouldBe true
        rolledBack shouldBe 0
      }
    }

    "fail fast while another transaction holds the lock" in {
      val releaseLock = Promise[Unit]()
      val (lockAcquired, lockReleased) = holdLock(
        AdvisoryLocks.withTransactionalLock(profile, AdvisoryLockIds.acsSnapshotDataInsert, _),
        releaseLock.future,
      )
      for {
        _ <- lockAcquired
        failure <- storage.underlying
          .queryAndUpdate(
            AdvisoryLocks.withTransactionalLock(
              profile,
              AdvisoryLockIds.acsSnapshotDataInsert,
              sqlu"insert into active_parties (store_id, party, closed_round) values (3, 'contended', 1)",
            ),
            "contended action under transactional lock",
          )
          .failOnShutdown
          .failed
        contendedWhileLocked <- countParties("contended")
        _ = releaseLock.success(())
        _ <- lockReleased
        // The same action succeeds once the holder's transaction has ended.
        _ <- storage.underlying
          .queryAndUpdate(
            AdvisoryLocks.withTransactionalLock(
              profile,
              AdvisoryLockIds.acsSnapshotDataInsert,
              sqlu"insert into active_parties (store_id, party, closed_round) values (3, 'contended', 1)",
            ),
            "retried action under transactional lock",
          )
          .failOnShutdown
        contendedAfterRelease <- countParties("contended")
      } yield {
        failure shouldBe AdvisoryLocks.FailedToAcquireLockException(
          "transactional",
          AdvisoryLockIds.acsSnapshotDataInsert,
        )
        contendedWhileLocked shouldBe 0
        contendedAfterRelease shouldBe 1
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

  /** Whether [[lockId]] can be acquired, i.e. nothing is holding it. Session-scoped and
    * transactional locks share one lock space, so a session-scoped check also detects a
    * detects a transactional holder.
    */
  private def lockIsFree(lockId: Long): Future[Boolean] =
    storage.underlying
      .query(AdvisoryLocks.withSessionLock(lockId, DBIOAction.successful(true)), "check lock")
      .failOnShutdown
      .recover { case _: AdvisoryLocks.FailedToAcquireLockException => false }

  private def countParties(party: String): Future[Int] =
    storage.underlying
      .query(
        sql"select count(*) from active_parties where party = $party".as[Int].head,
        "countParties",
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
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = for {
    _ <- resetAllAppTables(storage)
    _ <- MonadUtil.sequentialTraverse(testIndexNames) { indexName =>
      storage.update(sqlu"drop index if exists #$indexName", s"drop index $indexName")
    }
  } yield ()
}

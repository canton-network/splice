// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.DbTest
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.store.StoreTestBase
import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*

trait AdvisoryLocksTestHelper { _: DbTest with StoreTestBase with HasExecutionContext =>

  /** Acquires a lock by calling [[withLock]] and holds it until `release` completes. Returns a
    * future that completes once the lock is held, and a future that completes once the lock has
    * been released.
    */
  final def holdLock(
      withLock: DBIOAction[Unit, NoStream, Effect.All] => DBIOAction[Unit, NoStream, Effect.All],
      action: DBIOAction[Unit, NoStream, Effect.All],
      release: Future[Unit],
  ): (Future[Unit], Future[Unit]) = {
    val acquired = Promise[Unit]()
    val released = storage.underlying
      .queryAndUpdate(
        withLock(action.map(_ => acquired.success(())).flatMap(_ => DBIOAction.from(release))),
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

  private val testTable = "a"

  private def commonTests(
      lockType: String,
      lockId: Long,
      withLock: (
          Long,
          DBIOAction[Unit, NoStream, Effect.All],
      ) => DBIOAction[Unit, NoStream, Effect.All],
  ) = {
    "release the lock after running an action" in {
      for {
        _ <- storage.underlying
          .queryAndUpdate(withLock(lockId, DBIOAction.unit), "test lock")
          .failOnShutdown
        lockIsFree <- lockIsFree(lockId)
      } yield lockIsFree shouldBe true
    }

    "release the lock after an action fails" in {
      for {
        failure <- storage.underlying
          .queryAndUpdate(
            withLock(lockId, DBIOAction.failed(new RuntimeException("error"))),
            "test lock",
          )
          .failOnShutdown
          .failed
        _ = failure shouldBe a[RuntimeException]
        lockIsFree <- lockIsFree(lockId)
      } yield lockIsFree shouldBe true
    }

    "fail fast while another session holds the lock" in {
      val releaseLock = Promise[Unit]()
      val createTable = sqlu"create table #$testTable (id int)".map(_ => ())
      val (lockAcquired, lockReleased) =
        holdLock(withLock(lockId, _), createTable, releaseLock.future)
      for {
        _ <- lockAcquired
        failure <- storage.underlying
          .queryAndUpdate(withLock(lockId, createTable), "contended query")
          .failOnShutdown
          .failed
        _ = releaseLock.success(())
        _ <- lockReleased
      } yield failure shouldBe AdvisoryLocks.FailedToAcquireLockException(lockType, lockId)
    }
  }

  "AdvisoryLocks.withSessionLock" should commonTests(
    "session-scoped",
    AdvisoryLockIds.ddlStatement,
    AdvisoryLocks.withSessionLock,
  )

  "AdvisoryLocks.withTransactionalLock" should commonTests(
    "transactional",
    AdvisoryLockIds.acsSnapshotDataInsert,
    AdvisoryLocks.withTransactionalLock(profile, _, _),
  )

  "AdvisoryLocks.withBlockingTransactionalLock" should {
    val lockId = AdvisoryLockIds.acsSnapshotIndex

    "release the lock after running an action" in {
      for {
        _ <- storage.underlying
          .queryAndUpdate(
            AdvisoryLocks.withBlockingTransactionalLock(profile, lockId, DBIOAction.unit),
            "test lock",
          )
          .failOnShutdown
        lockIsFree <- lockIsFree(lockId)
      } yield lockIsFree shouldBe true
    }

    "release the lock after an action fails" in {
      for {
        failure <- storage.underlying
          .queryAndUpdate(
            AdvisoryLocks.withBlockingTransactionalLock(
              profile,
              lockId,
              DBIOAction.failed(new RuntimeException("error")),
            ),
            "test lock",
          )
          .failOnShutdown
          .failed
        _ = failure shouldBe a[RuntimeException]
        lockIsFree <- lockIsFree(lockId)
      } yield lockIsFree shouldBe true
    }

    "wait while another session holds the lock and then run the action" in {
      val releaseLock = Promise[Unit]()
      val (lockAcquired, lockReleased) =
        holdLock(AdvisoryLocks.withSessionLock(lockId, _), DBIOAction.unit, releaseLock.future)
      for {
        _ <- lockAcquired
        contended = storage.underlying
          .queryAndUpdate(
            AdvisoryLocks.withBlockingTransactionalLock(
              profile,
              lockId,
              sqlu"create table #$testTable (id int)".map(_ => ()),
            ),
            "contended query",
          )
          .failOnShutdown
        _ <- awaitLockWaiter(lockId, attemptsLeft = 200)
        completedWhileLocked = contended.isCompleted
        _ = releaseLock.success(())
        _ <- lockReleased
        _ <- contended
        tableExists <- storage.underlying
          .query(
            sql"select to_regclass($testTable) is not null".as[Boolean].head,
            "check table",
          )
          .failOnShutdown
      } yield {
        completedWhileLocked shouldBe false
        tableExists shouldBe true
      }
    }
  }

  /** Whether [[lockId]] can be acquired, i.e. nothing is holding it. Session-scoped and
    * transactional locks share one lock space, so a session-scoped check also detects a
    * detects a transactional holder.
    */
  private def lockIsFree(lockId: Long): Future[Boolean] =
    storage.underlying
      .query(AdvisoryLocks.withSessionLock(lockId, DBIOAction.successful(true)), "check lock")
      .failOnShutdown
      .recover { case _: AdvisoryLocks.FailedToAcquireLockException => false }

  private def awaitLockWaiter(lockId: Long, attemptsLeft: Int): Future[Unit] =
    storage.underlying
      .query(
        sql"""select count(*) from pg_locks
              where locktype = 'advisory' and objid::bigint = $lockId and not granted"""
          .as[Int]
          .head,
        "count lock waiters",
      )
      .failOnShutdown
      .flatMap {
        case waiters if waiters > 0 => Future.unit
        case _ if attemptsLeft > 0 =>
          org.apache.pekko.pattern.after(50.millis)(awaitLockWaiter(lockId, attemptsLeft - 1))
        case _ => Future.failed(new IllegalStateException(s"No session waited for lock $lockId"))
      }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    storage.update(sqlu"drop table if exists #$testTable", s"drop $testTable")
}

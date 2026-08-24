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

  /** Whether [[lockId]] can be acquired, i.e. nothing is holding it. Session-scoped and
    * transactional locks share one lock space, so a session-scoped check also detects a
    * detects a transactional holder.
    */
  private def lockIsFree(lockId: Long): Future[Boolean] =
    storage.underlying
      .query(AdvisoryLocks.withSessionLock(lockId, DBIOAction.successful(true)), "check lock")
      .failOnShutdown
      .recover { case _: AdvisoryLocks.FailedToAcquireLockException => false }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    storage.update(sqlu"drop table if exists #$testTable", s"drop $testTable")
}

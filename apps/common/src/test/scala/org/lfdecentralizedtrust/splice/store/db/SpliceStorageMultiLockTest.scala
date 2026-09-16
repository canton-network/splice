// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.config.{
  DbLockedConnectionConfig,
  DbLockedConnectionPoolConfig,
  DefaultProcessingTimeouts,
  PositiveFiniteDuration,
}
import com.digitalasset.canton.lifecycle.{CloseContext, FlagCloseable, FutureUnlessShutdown}
import com.digitalasset.canton.metrics.CommonMockMetrics
import com.digitalasset.canton.resource.{DbLockCounter, DbStorage, DbStorageMulti}
import com.digitalasset.canton.store.db.DbStorageSetup
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasExecutionContext}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AsyncWordSpec

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future

/** Verifies that [[SpliceStorageFactory]] (via [[DbStorageMulti]]) prevents two app instances
  * from being simultaneously active against the same Postgres database.
  *
  * Uses lock counters beyond the Splice production range so that this test does not collide
  * with [[SpliceDbLockCounters]] at class-initialization time.
  */
class SpliceStorageMultiLockTest
    extends AsyncWordSpec
    with BaseTest
    with HasExecutionContext
    with SplicePostgresTest
    with BeforeAndAfterAll {

  // Use counters in the test range (77+) that won't clash with SpliceDbLockCounters (100+).
  private val mainCounter: DbLockCounter = DbLockCounter(200)
  private val poolCounter: DbLockCounter = DbLockCounter(201)

  private val connectionPoolConfig = DbLockedConnectionPoolConfig(
    connection = DbLockedConnectionConfig(
      healthCheckPeriod = PositiveFiniteDuration.ofMillis(200),
      passiveCheckPeriod = PositiveFiniteDuration.ofMillis(200),
    ),
    healthCheckPeriod = PositiveFiniteDuration.ofMillis(200),
    activeTimeout = PositiveFiniteDuration.ofSeconds(5),
  )

  // Capture the setup so we can access its config for DbStorageMulti.create (setup is private in DbTest).
  private var capturedSetup: DbStorageSetup = _
  override protected def createSetup(): DbStorageSetup = {
    capturedSetup = super.createSetup()
    capturedSetup
  }

  private def createStorage(
      name: String,
      onPassive: () => Unit = () => (),
  ): DbStorageMulti = {
    val writePoolSize = PositiveInt.tryCreate(2)
    val readPoolSize = capturedSetup.config.numReadConnectionsCanton(
      forParticipant = false,
      withWriteConnectionPool = true,
      withMainConnection = false,
    )
    val sessionCtx = CloseContext(FlagCloseable(logger, timeouts))
    DbStorageMulti
      .create(
        capturedSetup.config,
        connectionPoolConfig,
        readPoolSize,
        writePoolSize,
        mainCounter,
        poolCounter,
        onActive = _ => FutureUnlessShutdown.unit,
        onPassive = { tracedLogger =>
          tracedLogger.info(s"[$name] onPassive called")
          FutureUnlessShutdown.pure(onPassive())
        },
        mustStayActive = false,
        CommonMockMetrics.dbStorage,
        None,
        customClock = None,
        None,
        DefaultProcessingTimeouts.testing,
        exitOnFatalFailures = false,
        futureSupervisor,
        loggerFactory.append("storageId", name),
        () => sessionCtx,
      )
      .valueOrFailShutdown("create DB storage")
  }

  override def cleanDb(storage: DbStorage)(implicit tc: TraceContext): FutureUnlessShutdown[?] =
    FutureUnlessShutdown.unit

  "SpliceStorageFactory DB lock" should {

    "allow only one active instance at a time" in {
      val storage1 = createStorage("instance-1")
      val storage2 = createStorage("instance-2")
      try {
        eventually() {
          assert(storage1.isActive || storage2.isActive, "at least one instance should be active")
        }
        assert(
          !(storage1.isActive && storage2.isActive),
          "both instances must not be active simultaneously",
        )
        val (activeStorage, passiveStorage) =
          if (storage1.isActive) (storage1, storage2) else (storage2, storage1)
        activeStorage.close()
        eventually() {
          assert(passiveStorage.isActive, "previously passive instance should become active")
        }
        Future.successful(succeed)
      } finally {
        storage1.close()
        storage2.close()
      }
    }

    "call onPassive when another instance holds the lock" in {
      val passiveCalled = new AtomicBoolean(false)
      val storage1 = createStorage("lock-holder")
      val storage2 = createStorage("lock-waiter", onPassive = () => passiveCalled.set(true))
      try {
        eventually() { assert(storage1.isActive || storage2.isActive) }
        eventually() {
          assert(
            !(storage1.isActive && storage2.isActive),
            "only one instance must hold the lock",
          )
        }
        Future.successful(succeed)
      } finally {
        storage1.close()
        storage2.close()
      }
    }
  }
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{ReplicationConfig, StorageConfig}
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{
  DbLockCounter,
  StorageFactory,
  StorageMultiFactory,
  StorageSingleFactory,
}
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.atomic.AtomicReference

/** Builds a [[StorageFactory]] for Splice app bootstrap classes.
  *
  * When [[instanceLockEnabled]] is true and the underlying storage is Postgres, the factory
  * produces a [[com.digitalasset.canton.resource.DbStorageMulti]] whose write-connection pool
  * holds an exclusive advisory lock.  Only the instance that holds the lock is "active"; if a
  * second instance starts against the same database it will wait for the lock and shut itself
  * down after the acquisition timeout.  When the active instance restarts and reclaims the
  * lock the waiting instance shuts down cleanly.
  *
  * When [[instanceLockEnabled]] is false, or when the storage is H2 / in-memory, the factory
  * falls back to [[com.digitalasset.canton.resource.DbStorageSingle]] with no locking
  * (preserving existing test behaviour).
  */
object SpliceStorageFactory {

  /** Builds a [[StorageFactory]] and wires the deferred-close pattern used by all Splice bootstrap
    * classes: the `onPassive` callback calls `close()` on the bootstrap object, but the bootstrap
    * doesn't exist yet when the factory is created, so a reference is filled in after construction.
    *
    * @param build receives the [[StorageFactory]] and must return the fully-constructed bootstrap;
    *              the bootstrap's `close()` is automatically connected to the passive callback.
    */
  def createWithDeferredClose[B <: AutoCloseable](
      storage: StorageConfig,
      instanceLockEnabled: Boolean,
      mainLockCounter: DbLockCounter,
      poolLockCounter: DbLockCounter,
      exitOnFatalFailures: Boolean,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
  )(build: StorageFactory => Either[String, B]): Either[String, B] = {
    val closeRef = new AtomicReference[() => Unit](() => ())
    val storageFactory = create(
      storage = storage,
      instanceLockEnabled = instanceLockEnabled,
      mainLockCounter = mainLockCounter,
      poolLockCounter = poolLockCounter,
      exitOnFatalFailures = exitOnFatalFailures,
      futureSupervisor = futureSupervisor,
      loggerFactory = loggerFactory,
      onPassive = () => closeRef.get()(),
    )
    build(storageFactory).map { result =>
      closeRef.set(() => result.close())
      result
    }
  }

  private def create(
      storage: StorageConfig,
      instanceLockEnabled: Boolean,
      mainLockCounter: DbLockCounter,
      poolLockCounter: DbLockCounter,
      exitOnFatalFailures: Boolean,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
      onPassive: () => Unit,
  ): StorageFactory =
    if (!instanceLockEnabled) new StorageSingleFactory(storage)
    else
      new StorageMultiFactory(
        config = storage,
        exitOnFatalFailures = exitOnFatalFailures,
        replicationConfig = ReplicationConfig.withDefaultO(storage, None),
        onActive = _ => FutureUnlessShutdown.unit,
        onPassive = logger => {
          implicit val tc: TraceContext = TraceContext.empty
          logger.error(
            "Lost DB instance lock – another node instance may have taken over. Shutting down."
          )
          FutureUnlessShutdown.pure(onPassive())
        },
        mustStayActive = true,
        mainLockCounter = mainLockCounter,
        poolLockCounter = poolLockCounter,
        futureSupervisor = futureSupervisor,
        loggerFactory = loggerFactory,
        getSessionContextO = None,
      )
}

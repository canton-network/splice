// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.ExecutionContext

object AdvisoryLocks {
  final case class FailedToAcquireAdvisoryLockException(lockId: Long)
      extends RuntimeException(s"Failed to acquire advisory lock $lockId.")

  private[db] def acquireSessionLock(lockId: Long): DBIOAction[Boolean, NoStream, Effect.Read] =
    sql"select pg_try_advisory_lock($lockId)".as[Boolean].head

  private[db] def releaseSessionLock(lockId: Long): DBIOAction[Boolean, NoStream, Effect.Read] =
    sql"select pg_advisory_unlock($lockId)".as[Boolean].head

  /** Wraps the given action in a session-scoped advisory lock; useful for acquiring locks for
    * queries like DDL that can't run in a transaction.
    */
  def withSessionLock[T, E <: Effect](lockId: Long, action: DBIOAction[T, NoStream, E])(implicit
      ec: ExecutionContext
  ): DBIOAction[T, NoStream, Effect.Read & E] =
    (for {
      lockAcquired <- acquireSessionLock(lockId)
      result <- lockAcquired match {
        case true => action.andFinally(releaseSessionLock(lockId))
        case false => DBIOAction.failed(FailedToAcquireAdvisoryLockException(lockId))
      }
    } yield result).withPinnedSession

  def withDdlLock[T, E <: Effect](action: DBIOAction[T, NoStream, E])(implicit
      ec: ExecutionContext
  ): DBIOAction[T, NoStream, Effect.Read & E] =
    withSessionLock(AdvisoryLockIds.ddlStatement, action)
}

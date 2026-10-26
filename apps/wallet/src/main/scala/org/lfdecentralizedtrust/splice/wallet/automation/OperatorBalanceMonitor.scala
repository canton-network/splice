// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.KeyValueStore

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

/** Tracks the balance of the validator operator party.
  *
  * Persists the last time the balance was seen at or below `maxAllowedBalance`, so that
  * automation can tell how long the balance has been continuously above the limit,
  * also across restarts. Logs whenever the observed balance changes.
  */
class OperatorBalanceMonitor(
    keyValueStore: KeyValueStore,
    maxAllowedBalance: BigDecimal,
    clock: Clock,
    override protected val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  import OperatorBalanceMonitor.*

  // `None` until the first call to `observe`, which loads the persisted value.
  private val lastBelowLimit = new AtomicReference[Option[CantonTimestamp]](None)
  private val lastBalance = new AtomicReference[Option[BigDecimal]](None)

  /** Records the current balance of the operator party (unlocked plus locked). */
  def observe(balance: BigDecimal)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    val now = clock.now
    val previousBalance = lastBalance.getAndSet(Some(balance))
    if (!previousBalance.contains(balance)) {
      logger.debug(
        s"Operator balance changed from ${previousBalance.fold("<unknown>")(_.toString)} to $balance " +
          s"(max allowed balance: $maxAllowedBalance)"
      )
    }
    if (balance <= maxAllowedBalance) persist(now)
    else
      lastBelowLimit.get match {
        case Some(_) => Future.unit
        case None =>
          // First observation since startup and the balance is above the limit: continue from the persisted value.
          // If there is none (or it is invalid), start counting from now so that we never act on
          // a balance we have not observed for the full duration.
          keyValueStore
            .readValueAndLogOnDecodingFailure[Long](lastBelowLimitKey)
            .value
            .flatMap { storedMicrosO =>
              val stored = storedMicrosO.map(CantonTimestamp.assertFromLong)
              // A value in the future (e.g., after a clock reset) is not trustworthy.
              val initial = stored.filter(_ <= now).getOrElse(now)
              if (stored.contains(initial)) {
                lastBelowLimit.set(Some(initial))
                Future.unit
              } else persist(initial)
            }
      }
  }

  private def persist(timestamp: CantonTimestamp)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] =
    keyValueStore
      .setValue(lastBelowLimitKey, timestamp.toMicros)
      .map { _ =>
        lastBelowLimit.set(Some(timestamp))
        logger.info(
          s"Stored $timestamp as the last time the operator balance was at or below the limit of $maxAllowedBalance"
        )
      }

  /** True if the balance has been above the limit for longer than `maxDuration`. */
  def hasBeenAboveLimitFor(maxDuration: Duration): Boolean =
    lastBelowLimit.get.exists(below => clock.now > below.plus(maxDuration))
}

object OperatorBalanceMonitor {
  val lastBelowLimitKey = "validator_operator_last_below_balance_limit"
}

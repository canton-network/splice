// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.treasury

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.wallet.store.PartyBalanceMonitorStore

import java.time.Duration
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{ExecutionContext, Future}

/** Tracks the balance of a single wallet party.
  *
  * Persists the last time the balance was seen at or below `maxAllowedBalance`, so that
  * the treasury can tell how long the balance has been continuously above the limit,
  * also across restarts. Logs whenever the observed balance changes.
  */
class PartyBalanceMonitor(
    store: PartyBalanceMonitorStore,
    maxAllowedBalance: BigDecimal,
    maxTimeAboveLimit: Duration,
    clock: Clock,
    override protected val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  // `None` until the first call to `observe`, which loads the persisted value.
  private val lastBelowLimit = new AtomicReference[Option[CantonTimestamp]](None)
  private val lastBalance = new AtomicReference[Option[BigDecimal]](None)
  private val gateClosed = new AtomicBoolean(false)

  /** Records the current balance of the party (unlocked plus locked). */
  def observe(balance: BigDecimal)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    val now = clock.now
    val previousBalance = lastBalance.getAndSet(Some(balance))
    if (!previousBalance.contains(balance)) {
      logger.debug(
        s"Party balance changed from ${previousBalance.fold("<unknown>")(_.toString)} to $balance " +
          s"(max allowed balance: $maxAllowedBalance)"
      )
    }
    updateLastBelowLimit(balance, now).map(_ => logGateTransition(balance))
  }

  private def updateLastBelowLimit(balance: BigDecimal, now: CantonTimestamp)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    val aboveLimit = balance > maxAllowedBalance
    if (aboveLimit && lastBelowLimit.get.isDefined) Future.unit
    else
      for {
        // First observation since startup and the balance is above the limit: continue from the persisted value.
        // If there is none, start counting from now so that we never act on
        // a balance we have not observed for the full duration.
        stored <- store.getLastBelowLimit()
        newLastBelowLimit <- stored match {
          case Some(storedTimestamp) if aboveLimit => Future.successful(storedTimestamp)
          case _ =>
            store
              .setLastBelowLimit(now)
              .map { _ =>
                logger.debug(
                  s"Stored $now as the last time the party balance was at or below the limit of $maxAllowedBalance"
                )
                now
              }
        }
      } yield lastBelowLimit.set(Some(newLastBelowLimit))
  }

  private def logGateTransition(balance: BigDecimal)(implicit tc: TraceContext): Unit = {
    val closed = !shouldCollectRewards
    if (gateClosed.getAndSet(closed) != closed) {
      if (closed)
        logger.warn(
          s"Party balance $balance has been above the allowed maximum of $maxAllowedBalance " +
            s"for more than $maxTimeAboveLimit; rewards will no longer be collected"
        )
      else
        logger.info(
          s"Party balance $balance is back within the allowed maximum of $maxAllowedBalance; " +
            "rewards are collected again"
        )
    }
  }

  /** True if the balance has been above the limit for longer than `maxDuration`. */
  def hasBeenAboveLimitFor(maxDuration: Duration): Boolean =
    lastBelowLimit.get.exists(below => clock.now > below.plus(maxDuration))

  /** False if the balance has been above the limit for longer than the configured `maxTimeAboveLimit`,
    * in which case the party must not collect rewards.
    */
  def shouldCollectRewards: Boolean = !hasBeenAboveLimitFor(maxTimeAboveLimit)
}

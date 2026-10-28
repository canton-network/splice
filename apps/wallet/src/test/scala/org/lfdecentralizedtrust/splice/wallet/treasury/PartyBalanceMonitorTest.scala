// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.treasury

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTestWordSpec, HasExecutionContext}
import org.lfdecentralizedtrust.splice.wallet.store.PartyBalanceMonitorStore
import org.slf4j.event.Level

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

class PartyBalanceMonitorTest extends BaseTestWordSpec with HasExecutionContext {

  private class InMemoryStore extends PartyBalanceMonitorStore {
    val value = new AtomicReference[Option[CantonTimestamp]](None)
    override def getLastBelowLimit()(implicit tc: TraceContext): Future[Option[CantonTimestamp]] =
      Future.successful(value.get)
    override def setLastBelowLimit(timestamp: CantonTimestamp)(implicit
        tc: TraceContext
    ): Future[Unit] = Future.successful(value.set(Some(timestamp)))
  }

  private val limit = BigDecimal(1000)
  private val day = Duration.ofHours(24)
  private val closedWarning = "rewards will no longer be collected"

  private class Fixture {
    val clock = new SimClock(loggerFactory = loggerFactory)
    val store = new InMemoryStore
    def mkMonitor(
        s: PartyBalanceMonitorStore = store,
        window: Duration = day,
    ) = new PartyBalanceMonitor(s, limit, window, clock, loggerFactory)
    val monitor = mkMonitor()

    def observe(balance: BigDecimal, m: PartyBalanceMonitor = monitor): Unit =
      m.observe(balance).futureValue

    def persisted: Option[CantonTimestamp] = store.value.get
  }

  "PartyBalanceMonitor" should {

    "persist the time at which the balance was last seen at or below the limit" in {
      val f = new Fixture
      f.observe(limit)
      f.persisted shouldBe Some(f.clock.now)

      f.clock.advance(Duration.ofHours(1))
      f.observe(limit - 1)
      f.persisted shouldBe Some(f.clock.now)
    }

    "not update the persisted time while the balance is above the limit" in {
      val f = new Fixture
      f.observe(limit)
      val below = f.clock.now

      f.clock.advance(Duration.ofHours(1))
      f.observe(limit + 1)
      f.persisted shouldBe Some(below)
    }

    "close the gate only after the configured duration and reopen it when the balance drops" in {
      val f = new Fixture
      f.observe(limit)
      f.monitor.shouldCollectRewards shouldBe true

      f.clock.advance(Duration.ofHours(23))
      f.observe(limit + 1)
      f.monitor.shouldCollectRewards shouldBe true

      loggerFactory.assertLogs(
        {
          f.clock.advance(Duration.ofHours(2))
          f.observe(limit + 1)
        },
        _.warningMessage should include(closedWarning),
      )
      f.monitor.shouldCollectRewards shouldBe false

      f.observe(limit)
      f.monitor.shouldCollectRewards shouldBe true
    }

    "respect the configured window" in {
      val f = new Fixture
      val short = f.mkMonitor(window = Duration.ofHours(1))
      val long = f.mkMonitor(new InMemoryStore, window = Duration.ofHours(48))
      f.observe(limit, short)
      f.observe(limit, long)

      f.clock.advance(Duration.ofHours(2))
      loggerFactory.assertLogs(
        {
          f.observe(limit + 1, short)
          f.observe(limit + 1, long)
        },
        _.warningMessage should include(closedWarning),
      )
      short.shouldCollectRewards shouldBe false
      long.shouldCollectRewards shouldBe true
    }

    "log the open-to-closed transition exactly once" in {
      val f = new Fixture
      f.observe(limit)
      f.clock.advance(Duration.ofHours(25))
      loggerFactory.assertLogs(
        {
          f.observe(limit + 1)
          f.observe(limit + 2)
          f.observe(limit + 3)
        },
        _.warningMessage should include(closedWarning),
      )
    }

    "continue from the persisted time after a restart" in {
      val f = new Fixture
      f.observe(limit)
      f.clock.advance(Duration.ofHours(30))

      val restarted = f.mkMonitor()
      loggerFactory.suppressWarnings(f.observe(limit + 1, restarted))
      restarted.hasBeenAboveLimitFor(day) shouldBe true
    }

    "start counting from now if nothing was persisted and the balance is above the limit" in {
      val f = new Fixture
      f.observe(limit + 1)
      f.persisted shouldBe Some(f.clock.now)
      f.monitor.hasBeenAboveLimitFor(day) shouldBe false

      f.clock.advance(Duration.ofHours(25))
      f.monitor.hasBeenAboveLimitFor(day) shouldBe true
    }

    "keep independent state for different parties" in {
      val f = new Fixture
      val other = f.mkMonitor(new InMemoryStore)
      f.observe(limit)
      f.observe(limit, other)
      f.clock.advance(Duration.ofHours(25))
      loggerFactory.assertLogs(
        f.observe(limit + 1),
        _.warningMessage should include(closedWarning),
      )
      f.observe(limit, other)
      f.monitor.shouldCollectRewards shouldBe false
      other.shouldCollectRewards shouldBe true
    }

    "log at info level only on gate transitions" in {
      val f = new Fixture
      loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.INFO))(
        {
          f.observe(limit)
          f.observe(limit - 1)
          f.observe(limit + 1)
          f.observe(limit + 2)
        },
        entries => entries shouldBe empty,
      )
      f.clock.advance(Duration.ofHours(25))
      loggerFactory.assertLogs(
        f.observe(limit + 1),
        _.warningMessage should include(closedWarning),
      )
      loggerFactory.assertLogs(SuppressionRule.Level(Level.INFO))(
        {
          f.observe(limit)
          f.observe(limit - 1)
        },
        _.infoMessage should include("rewards are collected again"),
      )
    }
  }
}

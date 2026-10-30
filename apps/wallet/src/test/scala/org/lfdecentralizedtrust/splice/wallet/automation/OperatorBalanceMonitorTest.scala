// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import cats.data.OptionT
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, SuppressionRule}
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTestWordSpec, HasExecutionContext}
import io.circe.{Decoder, Encoder, Json}
import org.lfdecentralizedtrust.splice.store.KeyValueStore
import org.slf4j.event.Level

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Future

class OperatorBalanceMonitorTest extends BaseTestWordSpec with HasExecutionContext {

  private class InMemoryKeyValueStore(val loggerFactory: NamedLoggerFactory) extends KeyValueStore {
    val values = new ConcurrentHashMap[String, Json]()

    override def setValue[T](key: String, value: T)(implicit
        tc: TraceContext,
        encoder: Encoder[T],
    ): Future[Unit] = Future.successful(values.put(key, encoder(value))).map(_ => ())

    override def setValueIfNotExists[T](key: String, value: T)(implicit
        tc: TraceContext,
        encoder: Encoder[T],
    ): Future[Boolean] =
      Future.successful(values.putIfAbsent(key, encoder(value)) == null)

    override def getValue[T](key: String)(implicit
        tc: TraceContext,
        decoder: Decoder[T],
    ): OptionT[Future, Decoder.Result[T]] =
      OptionT(Future.successful(Option(values.get(key)).map(decoder.decodeJson)))

    override def deleteKey(key: String)(implicit tc: TraceContext): Future[Unit] =
      Future.successful(values.remove(key)).map(_ => ())
  }

  private val limit = BigDecimal(1000)
  private val day = Duration.ofHours(24)

  private class Fixture {
    val clock = new SimClock(loggerFactory = loggerFactory)
    val kvStore = new InMemoryKeyValueStore(loggerFactory)
    def mkMonitor() = new OperatorBalanceMonitor(kvStore, limit, clock, loggerFactory)
    val monitor = mkMonitor()

    def observe(balance: BigDecimal, m: OperatorBalanceMonitor = monitor): Unit =
      m.observe(balance).futureValue

    def persisted: Option[CantonTimestamp] =
      Option(kvStore.values.get(OperatorBalanceMonitor.lastBelowLimitKey))
        .flatMap(_.as[Long].toOption)
        .map(CantonTimestamp.assertFromLong)
  }

  "OperatorBalanceMonitor" should {

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

    "report the balance as above the limit for longer than a given duration only after that duration" in {
      val f = new Fixture
      f.observe(limit)
      f.clock.advance(Duration.ofHours(23))
      f.observe(limit + 1)
      f.monitor.hasBeenAboveLimitFor(day) shouldBe false

      f.clock.advance(Duration.ofHours(2))
      f.observe(limit + 1)
      f.monitor.hasBeenAboveLimitFor(day) shouldBe true
    }

    "reset the duration when the balance drops back to the limit" in {
      val f = new Fixture
      f.observe(limit)
      f.clock.advance(Duration.ofHours(30))
      f.observe(limit + 1)
      f.monitor.hasBeenAboveLimitFor(day) shouldBe true

      f.observe(limit)
      f.monitor.hasBeenAboveLimitFor(day) shouldBe false
    }

    "only allow collecting rewards until the balance has been above the limit for 24 hours" in {
      val f = new Fixture
      f.observe(limit)
      f.monitor.shouldCollectRewards shouldBe true

      f.clock.advance(Duration.ofHours(23))
      f.observe(limit + 1)
      f.monitor.shouldCollectRewards shouldBe true

      f.clock.advance(Duration.ofHours(2))
      f.observe(limit + 1)
      f.monitor.shouldCollectRewards shouldBe false

      f.observe(limit)
      f.monitor.shouldCollectRewards shouldBe true
    }

    "continue from the persisted time after a restart" in {
      val f = new Fixture
      f.observe(limit)
      f.clock.advance(Duration.ofHours(30))

      val restarted = f.mkMonitor()
      f.observe(limit + 1, restarted)
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

    "ignore a persisted time in the future" in {
      val f = new Fixture
      val future = f.clock.now.plus(Duration.ofDays(10))
      f.kvStore.setValue(OperatorBalanceMonitor.lastBelowLimitKey, future.toMicros).futureValue

      f.observe(limit + 1)
      f.persisted shouldBe Some(f.clock.now)
      f.monitor.hasBeenAboveLimitFor(day) shouldBe false
    }

    "log at info level only when the stored value changes" in {
      val f = new Fixture
      loggerFactory.assertLogs(
        SuppressionRule.Level(Level.INFO)
      )(
        f.observe(limit),
        _.infoMessage should include("Stored"),
      )
      // No info logs when the balance is above the limit, even though it changes
      loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.INFO))(
        {
          f.observe(limit + 1)
          f.observe(limit + 2)
        },
        entries => entries shouldBe empty,
      )
    }
  }
}

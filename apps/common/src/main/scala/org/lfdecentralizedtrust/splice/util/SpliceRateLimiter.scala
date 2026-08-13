// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.metrics.CacheMetrics
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.MetricQualification.Saturation
import com.daml.metrics.api.{MetricHandle, MetricInfo, MetricsContext}
import com.digitalasset.canton.caching.{CaffeineCache, ConcurrentCache}
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.LifeCycle
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import com.github.benmanes.caffeine.cache.{Caffeine, RemovalCause, RemovalListener}
import com.google.common.util.concurrent.{BurstyRateLimiterFactory, RateLimiter}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics

import java.time.{Duration, Instant}
import java.util
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.Future
import scala.jdk.CollectionConverters.CollectionHasAsScala

case class SpliceRateLimitMetrics(
    otelFactory: LabeledMetricsFactory,
    private val logger: TracedLogger,
)(implicit
    mc: MetricsContext
) extends AutoCloseable {

  private val gaugesToClose = Collections.synchronizedList(new util.ArrayList[AutoCloseable]())

  val meter: MetricHandle.Meter = otelFactory.meter(
    MetricInfo(
      SpliceMetrics.MetricsPrefix :+ "rate_limiting",
      "Rate limits applied in the node",
      Saturation,
    )
  )

  /*we need to pass the full context when we create it to avoid duplicate values warnings*/
  def recordMaxLimit(limit: Double)(implicit extraMc: MetricsContext): Unit = {
    val createdGauge = otelFactory.gauge[Double](
      MetricInfo(
        SpliceMetrics.MetricsPrefix :+ "rate_limiting_max_limit_per_second",
        "Max allowed rate per second",
        Saturation,
      ),
      limit,
    )(mc.merge(extraMc))
    gaugesToClose.add(createdGauge).discard
  }

  override def close(): Unit = {
    val gaugesThatWillBeClosed = gaugesToClose.asScala.toSeq
    gaugesToClose.clear()
    LifeCycle.close(gaugesThatWillBeClosed*)(logger)
  }

}

sealed trait SpliceRateLimitConfig {

  def enabled: Boolean

  def ratePerSecond: Double

  def sustainedRatePerSecond: Option[Double]

  def sustainedWindowSeconds: Long
}

object SpliceRateLimitConfig {

  final case class Simple(
      enabled: Boolean = true,
      ratePerSecond: Double,
      sustainedRatePerSecond: Option[Double] = None,
      sustainedWindowSeconds: Long = SpliceRateLimiter.DefaultSustainedWindowSeconds,
  ) extends SpliceRateLimitConfig

  final case class WithPerClientIp(
      enabled: Boolean = true,
      ratePerSecond: Double,
      sustainedRatePerSecond: Option[Double] = None,
      sustainedWindowSeconds: Long = SpliceRateLimiter.DefaultSustainedWindowSeconds,
      perClientIp: PerAttributeRateLimitConfig = PerAttributeRateLimitConfig.Disabled,
  ) extends SpliceRateLimitConfig

  def apply(
      enabled: Boolean = true,
      ratePerSecond: Double,
      sustainedRatePerSecond: Option[Double] = None,
      sustainedWindowSeconds: Long = SpliceRateLimiter.DefaultSustainedWindowSeconds,
  ): Simple =
    Simple(enabled, ratePerSecond, sustainedRatePerSecond, sustainedWindowSeconds)
}

case class PerAttributeRateLimitConfig(
    enabled: Boolean = true,
    limit: SpliceRateLimitConfig.Simple = PerAttributeRateLimitConfig.DefaultLimit,
    maxAttributeValues: Long = 10000,
) {

  def rateLimitFor(overall: SpliceRateLimitConfig): SpliceRateLimitConfig.Simple =
    limit.copy(enabled = enabled && limit.enabled && overall.enabled)
}

object PerAttributeRateLimitConfig {
  val DefaultLimit: SpliceRateLimitConfig.Simple = SpliceRateLimitConfig(ratePerSecond = 10)
  val Disabled: PerAttributeRateLimitConfig = PerAttributeRateLimitConfig(enabled = false)
}

object SpliceRateLimiter {

  val GlobalLimiterType = "global"
  val PerAttributeLimiterType = "per-attribute"
  val UnknownAttributeLimiterType = "unknown-attribute"

  val DefaultSustainedWindowSeconds: Long = 60

  private[util] def sustainedWindow(config: SpliceRateLimitConfig): Duration =
    Duration.ofSeconds(Math.max(1L, config.sustainedWindowSeconds))
}

// noinspection UnstableApiUsage
class SpliceRateLimiter(
    name: String,
    config: SpliceRateLimitConfig,
    metrics: SpliceRateLimitMetrics,
    enforceAfter: Instant = Instant.now(),
    limiterType: String = SpliceRateLimiter.GlobalLimiterType,
    extraLabels: Map[String, String] = Map.empty,
    // must be disabled for the per-attribute limiters as they'd all report the same value
    // and would explode the number of registered gauges
    reportMaxLimit: Boolean = true,
) {

  private val metricsContext = MetricsContext(
    extraLabels ++ Map("limiter" -> name, "limiter_type" -> limiterType)
  )

  // The limiters are created eagerly so that they are already "warm" (i.e. have accumulated their
  // burst budget) by the time the limit starts being enforced. They are only created for enabled
  // limiters: a disabled limiter is never consulted and its configured rate might not even be a
  // valid guava rate (e.g. 0).
  // enforces the per-second burst limit (checked over a 1s window)
  private val limiter: Option[RateLimiter] =
    Option.when(config.enabled)(RateLimiter.create(config.ratePerSecond))
  // enforces the sustained limit over the sustained window, while still allowing bursts within its budget.
  private val sustainedLimiter: Option[RateLimiter] =
    Option
      .when(config.enabled)(config.sustainedRatePerSecond)
      .flatten
      .map(
        BurstyRateLimiterFactory
          .create(_, SpliceRateLimiter.sustainedWindow(config).toSeconds.toDouble)
      )
  // lazy to ensure metrics get registered only if the limiter is actually used
  private lazy val rateLimiter: Option[RateLimiter] = {
    if (reportMaxLimit) {
      metrics
        .recordMaxLimit(config.ratePerSecond)(metricsContext)
    }
    limiter
  }

  def markRun(): Boolean = {
    if (config.enabled && Instant.now().isAfter(enforceAfter)) {
      val canRun = rateLimiter.forall(_.tryAcquire()) && sustainedLimiter.forall(_.tryAcquire())
      if (canRun) {
        metrics.meter.mark()(
          metricsContext.merge(MetricsContext("result" -> "accepted"))
        )
      } else {
        metrics.meter.mark()(
          metricsContext.merge(MetricsContext("result" -> "rejected"))
        )
      }
      canRun
    } else true
  }

  def runWithLimit[T](f: => Future[T]): Future[T] = {
    if (markRun()) {
      f
    } else {
      Future.failed(
        io.grpc.Status.RESOURCE_EXHAUSTED
          .withDescription("Rate limit exceeded")
          .asRuntimeException()
      )
    }
  }

}

class PerAttributeRateLimiter(
    name: String,
    attribute: String,
    config: SpliceRateLimitConfig,
    attributeConfig: PerAttributeRateLimitConfig,
    metrics: SpliceRateLimitMetrics,
    enforceAfter: Instant = Instant.now(),
    logger: TracedLogger,
) {

  private val perAttributeConfig = attributeConfig.rateLimitFor(config)
  private val isEnabled = perAttributeConfig.enabled && perAttributeConfig.ratePerSecond > 0
  private val attributeLabel = Map("limiter_attribute" -> attribute)

  // evictions by size can happen for every single request (e.g. when a large number of distinct
  // attribute values is seen), so the warning is throttled to avoid flooding the logs
  private val lastSizeEvictionWarning =
    new AtomicLong(System.nanoTime() - PerAttributeRateLimiter.EvictionWarningIntervalNanos)

  private val evictionListener: RemovalListener[String, SpliceRateLimiter] =
    (key: String, _: SpliceRateLimiter, cause: RemovalCause) => {
      if (cause == RemovalCause.SIZE) {
        implicit val tc: TraceContext = TraceContext.empty
        val message =
          s"Rate limiter cache for $name (attribute '$attribute') exceeded its maximum size of " +
            s"${attributeConfig.maxAttributeValues}; evicting the rate limiter for attribute value '$key'. " +
            "Its rate limiting state is lost. Consider increasing max-attribute-values."
        val now = System.nanoTime()
        val last = lastSizeEvictionWarning.get()
        if (
          now - last >= PerAttributeRateLimiter.EvictionWarningIntervalNanos && lastSizeEvictionWarning
            .compareAndSet(last, now)
        ) {
          logger.warn(message)
        } else {
          logger.debug(message)
        }
      }
    }

  // lazy so that neither the cache nor its metrics are created if the limiter is disabled
  private lazy val cache: ConcurrentCache[String, SpliceRateLimiter] = CaffeineCache[
    String,
    SpliceRateLimiter,
  ](
    Caffeine
      .newBuilder()
      .maximumSize(attributeConfig.maxAttributeValues)
      // Evict limiters that have not been used for a full sustained rate limiting window (the bucket
      // size of the interval rate limiter): after that time an idle limiter would have refilled its
      // budget anyway, so dropping it does not change the enforced rate.
      .expireAfterAccess(SpliceRateLimiter.sustainedWindow(perAttributeConfig))
      .evictionListener(evictionListener),
    Some(new CacheMetrics(s"$name-$attribute-rate-limiter", metrics.otelFactory)),
  )

  private lazy val defaultRateLimiter = new SpliceRateLimiter(
    name,
    perAttributeConfig,
    metrics,
    enforceAfter,
    limiterType = SpliceRateLimiter.UnknownAttributeLimiterType,
    extraLabels = attributeLabel,
  )

  private lazy val reportedMaxLimit: Unit =
    metrics.recordMaxLimit(perAttributeConfig.ratePerSecond)(
      MetricsContext(
        attributeLabel ++ Map(
          "limiter" -> name,
          "limiter_type" -> SpliceRateLimiter.PerAttributeLimiterType,
        )
      )
    )

  def markRun(attributeValue: Option[String]): Boolean =
    if (isEnabled) attributeValue.fold(defaultRateLimiter)(limiterFor).markRun()
    else true

  private def limiterFor(attributeValue: String): SpliceRateLimiter = {
    reportedMaxLimit
    cache.getOrAcquire(
      attributeValue,
      (_: String) =>
        new SpliceRateLimiter(
          name,
          perAttributeConfig,
          metrics,
          enforceAfter,
          limiterType = SpliceRateLimiter.PerAttributeLimiterType,
          extraLabels = attributeLabel,
          reportMaxLimit = false,
        ),
    )
  }
}

object PerAttributeRateLimiter {

  private val EvictionWarningIntervalNanos: Long = TimeUnit.MINUTES.toNanos(1)
}

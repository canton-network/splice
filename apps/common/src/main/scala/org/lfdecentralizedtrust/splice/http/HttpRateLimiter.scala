// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.logging.TracedLogger
import org.apache.pekko.http.scaladsl.model.headers.{`X-Forwarded-For`, `X-Real-Ip`}
import org.apache.pekko.http.scaladsl.model.{
  AttributeKeys,
  HttpEntity,
  HttpRequest,
  RemoteAddress,
  StatusCodes,
}
import org.apache.pekko.http.scaladsl.server.Directive0
import org.lfdecentralizedtrust.splice.config.RateLimitersConfig
import org.lfdecentralizedtrust.splice.util.{
  PerAttributeRateLimiter,
  SpliceRateLimitMetrics,
  SpliceRateLimiter,
}

import java.time.Instant

class HttpRateLimiter(
    config: RateLimitersConfig,
    metricsFactory: LabeledMetricsFactory,
    logger: TracedLogger,
) extends AutoCloseable {

  // need to cache it as the pekko routes get evaluated for each request
  private val rateLimiters =
    scala.collection.concurrent.TrieMap[String, (SpliceRateLimiter, PerAttributeRateLimiter)]()
  private val metrics = scala.collection.concurrent.TrieMap[String, SpliceRateLimitMetrics]()

  private def metricsFor(service: String): SpliceRateLimitMetrics =
    metrics.getOrElseUpdate(
      service,
      SpliceRateLimitMetrics(metricsFactory, logger)(
        MetricsContext(
          "http_service" -> service
        )
      ),
    )

  // the rate limiter has a cold start, to avoid the first request being rejected
  // we enforce the rate limit only after 1 second
  private def enforceAfter = Instant.now().plusSeconds(1)

  private val globalRateLimiter: (SpliceRateLimiter, PerAttributeRateLimiter) = {
    val globalMetrics = metricsFor(HttpRateLimiter.GlobalService)
    (
      new SpliceRateLimiter(
        HttpRateLimiter.GlobalLimiter,
        config.global,
        globalMetrics,
        enforceAfter,
      ),
      new PerAttributeRateLimiter(
        HttpRateLimiter.GlobalLimiter,
        HttpRateLimiter.ClientIpAttribute,
        config.global,
        config.global.perClientIp,
        globalMetrics,
        enforceAfter,
        logger,
      ),
    )
  }

  private def operationRateLimiter(
      service: String,
      operation: String,
  ): (SpliceRateLimiter, PerAttributeRateLimiter) =
    rateLimiters.getOrElseUpdate(
      operation, {
        val rateLimiterMetrics = metricsFor(service)
        val operationConfig = config.forRateLimiter(operation)
        (
          new SpliceRateLimiter(
            operation,
            operationConfig,
            rateLimiterMetrics,
            enforceAfter,
          ),
          new PerAttributeRateLimiter(
            operation,
            HttpRateLimiter.ClientIpAttribute,
            operationConfig,
            operationConfig.perClientIp,
            rateLimiterMetrics,
            enforceAfter,
            logger,
          ),
        )
      },
    )

  def withRateLimit(service: String)(operation: String): Directive0 = {
    val (globalLimiter, globalClientIpLimiter) = globalRateLimiter
    val (operationLimiter, operationClientIpLimiter) = operationRateLimiter(service, operation)

    import org.apache.pekko.http.scaladsl.server.Directives.*

    extractRequest.flatMap { request =>
      val clientIp = HttpRateLimiter.clientIp(request)
      // The global limiters are checked first so that a request rejected globally does not consume
      // budget from the per-operation limiters.
      val allowed =
        globalLimiter.markRun() &&
          globalClientIpLimiter.markRun(clientIp) &&
          operationLimiter.markRun() &&
          operationClientIpLimiter.markRun(clientIp)
      if (allowed) {
        pass
      } else {
        complete(
          StatusCodes.TooManyRequests,
          HttpEntity(
            "Too Many Requests: Server is busy, please try again later."
          ),
        )
      }
    }
  }

  def close(): Unit = metrics.view.values.foreach(_.close())
}

object HttpRateLimiter {

  private val ClientIpAttribute = "client_ip"

  private[splice] val GlobalLimiter = "global"
  private[splice] val GlobalService = "global"

  private[splice] def clientIp(request: HttpRequest): Option[String] =
    request
      .header[`X-Forwarded-For`]
      .flatMap(_.addresses.headOption)
      .orElse(request.header[`X-Real-Ip`].map(_.address))
      .orElse(request.attribute(AttributeKeys.remoteAddress))
      .collect { case RemoteAddress.IP(ip, _) => ip.getHostAddress }
}

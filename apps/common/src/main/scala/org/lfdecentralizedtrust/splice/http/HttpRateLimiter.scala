// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.logging.TracedLogger
import org.apache.pekko.http.scaladsl.model.{HttpEntity, RemoteAddress, StatusCodes}
import org.apache.pekko.http.scaladsl.server.{Directive0, Directive1}
import org.lfdecentralizedtrust.splice.config.RateLimitersConfig
import org.lfdecentralizedtrust.splice.util.{
  IpCidrRateLimits,
  PerAttributeRateLimiter,
  SpliceRateLimiter,
  SpliceRateLimitMetrics,
}

import java.net.{Inet6Address, InetAddress}

class HttpRateLimiter(
    config: RateLimitersConfig,
    metricsFactory: LabeledMetricsFactory,
    logger: TracedLogger,
) extends AutoCloseable {

  // need to cache it as the pekko routes get evaluated for each request
  // keyed by (service, operation) as the same operation name can be used by multiple services
  private val rateLimiters =
    scala.collection.concurrent.TrieMap[
      (String, String),
      (SpliceRateLimiter, PerAttributeRateLimiter),
    ]()
  private val metrics = scala.collection.concurrent.TrieMap[String, SpliceRateLimitMetrics]()

  private val clientIpHeaders: Seq[String] =
    config.clientIpHeaders.map(_.trim).filter(_.nonEmpty)

  private def metricsFor(service: String): SpliceRateLimitMetrics =
    metrics.getOrElseUpdate(
      service,
      SpliceRateLimitMetrics(metricsFactory, logger)(
        MetricsContext(
          "http_service" -> service
        )
      ),
    )

  private val globalRateLimiter: (SpliceRateLimiter, PerAttributeRateLimiter) = {
    val globalMetrics = metricsFor(HttpRateLimiter.GlobalService)
    (
      new SpliceRateLimiter(
        HttpRateLimiter.GlobalLimiter,
        config.global,
        globalMetrics,
      ),
      new PerAttributeRateLimiter(
        HttpRateLimiter.GlobalLimiter,
        HttpRateLimiter.ClientIpAttribute,
        config.global.perClientIp,
        globalMetrics,
        logger,
        IpCidrRateLimits.matchClientIp,
      ),
    )
  }

  private def operationRateLimiter(
      service: String,
      operation: String,
  ): (SpliceRateLimiter, PerAttributeRateLimiter) =
    rateLimiters.getOrElseUpdate(
      (service, operation), {
        val rateLimiterMetrics = metricsFor(service)
        val operationConfig = config.forRateLimiter(operation)
        (
          new SpliceRateLimiter(
            operation,
            operationConfig,
            rateLimiterMetrics,
          ),
          new PerAttributeRateLimiter(
            operation,
            HttpRateLimiter.ClientIpAttribute,
            operationConfig.perClientIp,
            rateLimiterMetrics,
            logger,
            IpCidrRateLimits.matchClientIp,
          ),
        )
      },
    )

  def withRateLimit(service: String)(operation: String): Directive0 = {
    val (globalLimiter, globalClientIpLimiter) = globalRateLimiter
    val (operationLimiter, operationClientIpLimiter) = operationRateLimiter(service, operation)

    import org.apache.pekko.http.scaladsl.server.Directives.*

    HttpRateLimiter
      .extractClientIpKey(clientIpHeaders)
      .flatMap { clientIp =>
        // The per client IP limiters are checked first (and `&&` short-circuits) so that a request
        // rejected because of its own client IP does not consume budget from the shared overall
        // limiters. Otherwise a single abusive client could exhaust the overall limits and thereby
        // deny service to all other clients.
        // Within each of those two groups the narrower per operation limiter is checked before the
        // global one, so that a request rejected for its operation does not consume global budget.
        val allowed =
          operationClientIpLimiter.markRun(clientIp) &&
            globalClientIpLimiter.markRun(clientIp) &&
            operationLimiter.markRun() &&
            globalLimiter.markRun()
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

  private[splice] def extractClientIpKey(
      clientIpHeaders: Seq[String] = RateLimitersConfig.DefaultClientIpHeaders
  ): Directive1[Option[String]] =
    ClientIpDirectives
      .extractClientIp(clientIpHeaders)
      .map(_.collect { case RemoteAddress.IP(ip, _) => rateLimitKey(ip) })

  /** Single clients are typically assigned a whole IPv6 /64 (or larger) network, so limiting per
    * full IPv6 address would allow a single client to trivially bypass the per client IP limit.
    * IPv6 addresses are therefore grouped by their /64 prefix, IPv4 addresses are used as is.
    */
  private def rateLimitKey(address: InetAddress): String = address match {
    case ipv6: Inet6Address =>
      val bytes = ipv6.getAddress
      ipv4Mapped(bytes) match {
        // connections accepted on a dual stack socket can report IPv4 clients as IPv4-mapped IPv6
        // addresses (e.g. ::ffff:192.0.2.1), those must use the same key as the plain IPv4 address
        case Some(ipv4) => ipv4.getHostAddress
        case None =>
          // zero out the lower 64 bits (the interface identifier), keeping the /64 network prefix
          // note that this also drops any scope/zone id, which is not meaningful for rate limiting
          val prefix = bytes.take(8) ++ Array.fill[Byte](8)(0)
          s"${InetAddress.getByAddress(prefix).getHostAddress}/64"
      }
    case ip => ip.getHostAddress
  }

  /** ::ffff:0:0/96, see [[https://www.rfc-editor.org/rfc/rfc4291#section-2.5.5.2]] */
  private val Ipv4MappedPrefix: Seq[Byte] =
    Seq.fill[Byte](10)(0) ++ Seq[Byte](0xff.toByte, 0xff.toByte)

  private def ipv4Mapped(bytes: Array[Byte]): Option[InetAddress] =
    Option.when(bytes.length == 16 && bytes.startsWith(Ipv4MappedPrefix))(
      InetAddress.getByAddress(bytes.drop(12))
    )
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.caching.ScaffeineCache
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.github.blemale.scaffeine.Scaffeine
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import cats.implicits.*
import com.daml.metrics.CacheMetrics
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.lifecycle.CloseContext
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import InternedStringStore.*

trait InternedStringStore {

  def getOrIntern(value: String)(implicit tc: TraceContext): Future[InternedId]

}

object InternedStringStore {
  type InternedId = Long

  def createAndWarmupCache(
      storage: DbStorage,
      maxSize: Long,
      ttl: FiniteDuration,
      loggerFactory: NamedLoggerFactory,
      metricsFactory: LabeledMetricsFactory,
  )(implicit
      ec: ExecutionContext,
      close: CloseContext,
      warmupTraceContext: TraceContext,
  ): Future[InternedStringStore] = {
    val cached = new CachedInternedStringsStore(
      new DbInternedStringStore(storage, loggerFactory),
      maxSize,
      ttl,
      loggerFactory,
      metricsFactory,
    )
    cached.warmup().map { _ => cached }
  }

  def createWithoutWarmup(
      storage: DbStorage,
      maxSize: Long,
      ttl: FiniteDuration,
      loggerFactory: NamedLoggerFactory,
      metricsFactory: LabeledMetricsFactory,
  )(implicit ec: ExecutionContext, close: CloseContext): InternedStringStore =
    new CachedInternedStringsStore(
      new DbInternedStringStore(storage, loggerFactory),
      maxSize,
      ttl,
      loggerFactory,
      metricsFactory,
    )

  class CachedInternedStringsStore(
      underlying: DbInternedStringStore,
      maxSize: Long,
      ttl: FiniteDuration,
      protected val loggerFactory: NamedLoggerFactory,
      metricsFactory: LabeledMetricsFactory,
  )(implicit ec: ExecutionContext)
      extends InternedStringStore
      with NamedLogging {

    private val CacheName = "interned-strings"

    private def cacheMetrics(metricsFactory: LabeledMetricsFactory) =
      new CacheMetrics(CacheName, metricsFactory)

    private val cache: ScaffeineCache.TracedAsyncLoadingCache[Future, String, InternedId] =
      ScaffeineCache.buildTracedAsync[Future, String, InternedId](
        Scaffeine()
          .expireAfterWrite(ttl)
          .maximumSize(maxSize),
        tc => key => underlying.getOrIntern(key)(tc),
        metrics = Some(cacheMetrics(metricsFactory)),
      )(logger, CacheName)

    override def getOrIntern(value: String)(implicit tc: TraceContext): Future[InternedId] =
      cache.get(value)

    private[InternedStringStore] def warmup()(implicit tc: TraceContext): Future[Unit] = {
      underlying.fetchAll(maxSize).map { all =>
        all.foreach { case (value, id) => cache.put(value, id) }
      }
    }

  }

  class DbInternedStringStore(storage: DbStorage, protected val loggerFactory: NamedLoggerFactory)(
      implicit
      ec: ExecutionContext,
      close: CloseContext,
  ) extends InternedStringStore
      with NamedLogging {

    override def getOrIntern(value: String)(implicit tc: TraceContext): Future[InternedId] = {
      storage
        .query(
          sql"""
            insert into interned_strings (value) values ($value)
            on conflict (value) do nothing returning id;
          """.as[InternedId].headOption,
          "intern",
        )
        .flatMap {
          case None =>
            storage.query(
              sql"select id from interned_strings where value = $value".as[InternedId].head,
              "getInternedId",
            )
          case Some(id) => Future.successful(id)
        }
    }

    private[InternedStringStore] def fetchAll(maxSize: Long)(implicit
        tc: TraceContext
    ): Future[Vector[(String, InternedId)]] = {
      storage
        .query(
          sql"select value, id from interned_strings limit $maxSize".as[(String, InternedId)],
          "fetchAllInternedStrings",
        )
        .map { all =>
          if (all.size >= maxSize) {
            logger.warn(
              s"Fetched ${all.size} interned strings, which is equal to or exceeds the configured max size of $maxSize. " +
                "This indicates that the cache is not large enough to hold all interned strings, which will reduce performance on cache misses."
            )
          }
          all
        }
    }

  }
}

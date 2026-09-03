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

  // TODO: warmup the cache
  def apply(
      storage: DbStorage,
      maxSize: Long,
      ttl: FiniteDuration,
      loggerFactory: NamedLoggerFactory,
      metricsFactory: LabeledMetricsFactory,
  )(implicit ec: ExecutionContext, close: CloseContext) = new CachedInternedStringsStore(
    new DbInternedStringStore(storage),
    maxSize,
    ttl,
    loggerFactory,
    metricsFactory,
  )
}

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

}

class DbInternedStringStore(storage: DbStorage)(implicit ec: ExecutionContext, close: CloseContext)
    extends InternedStringStore {

  override def getOrIntern(value: String)(implicit tc: TraceContext): Future[InternedId] = {
    storage
      .query(
        sql"""
            with new_row as (
              insert into interned_strings (value) values ($value)
              on conflict (value) do nothing returning id
            )
            select id from new_row union all select id from interned_strings where value = $value limit 1;
          """.as[InternedId].head,
        "intern",
      )
  }

}

package org.lfdecentralizedtrust.splice.performance.tests

import com.digitalasset.canton.config.{ProcessingTimeout, StorageConfig}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.HasIngestionSink
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  TransactionTreeUpdate,
  TreeUpdateOrOffsetCheckpoint,
}
import org.lfdecentralizedtrust.splice.store.{IngestedEvents, TreeUpdateWithMigrationId}

import cats.data.NonEmptyList
import io.circe.Json
import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

final case class StoreIngestionPerfMetrics(
    totalItems: Long,
    totalEvents: Long,
    totalBatches: Long,
    totalTimeNs: BigDecimal,
    processCpuTimeNs: BigDecimal,
    peakHeapBytes: BigDecimal,
) {
  def avgItemTimeNs: BigDecimal =
    if (totalItems > 0) totalTimeNs / totalItems else BigDecimal(0)

  def updatesPerSec: BigDecimal =
    if (totalTimeNs > 0) BigDecimal(totalItems) * BigDecimal(1000000000L) / totalTimeNs
    else BigDecimal(0)

  def eventsPerSec: BigDecimal =
    if (totalTimeNs > 0) BigDecimal(totalEvents) * BigDecimal(1000000000L) / totalTimeNs
    else BigDecimal(0)

  /** Ratio of process CPU time to wall-clock time.
    * Some thresholds we can use for rough classification:
    * > 0.7 : CPU-bound
    * 0.7 ~ 0.25: balanced
    * < 0.25 : I/O-bound
    */
  def cpuToWallClockRatio: BigDecimal =
    if (totalTimeNs > 0) processCpuTimeNs / totalTimeNs else BigDecimal(0)

  def ratePerSec(count: Long): BigDecimal =
    if (totalTimeNs > 0) BigDecimal(count) * BigDecimal(1000000000L) / totalTimeNs
    else BigDecimal(0)
}

object StoreIngestionPerfMetrics {

  val MetricsDir: String = "store-ingestion-perf-metrics"

  private val JobName = "splice_perf_ingestion"

  def metric(metricName: String, description: String, value: BigDecimal): Json = Json.obj(
    "name" -> Json.fromString(s"${JobName}_$metricName"),
    "description" -> Json.fromString(description),
    "value" -> Json.fromBigDecimal(value),
  )

  /** The common ingestion metrics, followed by any test-specific `extra` metrics.
    * The JSON structure keeps the Python side read-only, so new metrics can be added without changing the script.
    *
    * @param ingestionMetrics    raw measurements of the ingestion run
    * @param extraMetrics        test-specific metrics appended after the common ones
    * @param includeUpdateCounts whether to emit the update/event counts (`total_items`, `updates_per_sec`,
    *                            `total_events`, `events_per_sec`). The other common metrics are always emitted.
    *                            Tests that don't ingest updates (e.g. verdicts) set this to `false` and report
    *                            their own counts via `extraMetrics`.
    */
  def toMetricsJson(
      ingestionMetrics: StoreIngestionPerfMetrics,
      success: Boolean,
      extraMetrics: Seq[Json] = Seq.empty,
      includeUpdateCounts: Boolean = true,
  ): Json = {
    val completionEpochSec = java.time.Instant.now().getEpochSecond
    val successInt = if (success) 1 else 0
    val avgItemTime = metric(
      "avg_item_time_ns",
      "Average nanoseconds per ingested item",
      ingestionMetrics.avgItemTimeNs,
    )
    val updateCounts =
      if (!includeUpdateCounts) Seq.empty
      else
        Seq(
          metric(
            "total_items",
            "Total number of items ingested",
            BigDecimal(ingestionMetrics.totalItems),
          ),
          metric(
            "updates_per_sec",
            "Ingested updates per second",
            ingestionMetrics.updatesPerSec,
          ),
          metric(
            "total_events",
            "Total number of created + exercised/archived events across all ingested updates",
            BigDecimal(ingestionMetrics.totalEvents),
          ),
          metric(
            "events_per_sec",
            "Ingested events per second",
            ingestionMetrics.eventsPerSec,
          ),
        )
    val common = (avgItemTime +: updateCounts) ++ Seq(
      metric("total_time_ns", "Total ingestion time in nanoseconds", ingestionMetrics.totalTimeNs),
      metric(
        "total_batches",
        "Total number of batches ingested",
        BigDecimal(ingestionMetrics.totalBatches),
      ),
      metric(
        "completion_timestamp_seconds",
        "Epoch seconds when the test run completed",
        BigDecimal(completionEpochSec),
      ),
      metric("success", "Test run succeeded (1) or failed (0)", BigDecimal(successInt)),
      metric(
        "process_cpu_time_ns",
        "Process-wide CPU time in nanoseconds (all cores combined)",
        ingestionMetrics.processCpuTimeNs,
      ),
      metric(
        "peak_heap_bytes",
        "Peak JVM heap memory usage in bytes observed across all ingestion batches",
        ingestionMetrics.peakHeapBytes,
      ),
      metric(
        "cpu_to_wall_clock_ratio",
        "Ratio of process CPU time to wall-clock time (>0.7=CPU-bound, 0.25~0.7=standard, <0.25=I/O-bound)",
        ingestionMetrics.cpuToWallClockRatio,
      ),
    )
    Json.arr((common ++ extraMetrics)*)
  }
}

abstract class StoreIngestionPerformanceTest(
    updateHistoryDumpPath: Path,
    storageConfig: StorageConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
    ingestionConfig: IngestionConfig = IngestionConfig(),
)(implicit ec: ExecutionContext, actorSystem: ActorSystem)
    extends BaseStorePerformanceTest(
      updateHistoryDumpPath,
      storageConfig,
      timeouts,
      loggerFactory,
      ingestionConfig,
    ) {

  override type Store <: HasIngestionSink

  def run(): Future[Unit] = withStorage { storage =>
    TraceContext
      .withNewTraceContext(this.getClass.getName) { implicit tc =>
        for {
          store <- mkStore(storage)
          _ <- store.ingestionSink.initialize()
          _ <- sanityCheckTables(storage) { count =>
            Option.when(count != 0)(
              s"Expected table to be empty before ingestion, but found $count rows."
            )
          }
          txs = loadTxsFromDump()
          metrics <- ingestWithMetrics(store, txs)
          _ <- sanityCheckTables(storage) { count =>
            Option.when(count == 0)(
              s"Expected table to be non-empty after ingestion, but no rows were inserted."
            )
          }
          _ = writeIngestionMetrics(metrics, success = true)
        } yield ()
      }
  }

  // Ensuring that it's logged also on GHA console, as opposed to only in log files (which are not uploaded on success)
  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  private def ingestWithMetrics(store: Store, txs: Seq[TreeUpdateWithMigrationId])(implicit
      tc: TraceContext
  ): Future[StoreIngestionPerfMetrics] = {
    var totalTimeNs = BigDecimal(0)
    var totalItems = 0L
    var totalEvents = 0L
    var totalBatches = 0L
    var totalCpuNs = BigDecimal(0)
    var maxHeapBytes = BigDecimal(0)

    def eventsIn(update: TreeUpdateOrOffsetCheckpoint.Update): Long = update.update match {
      // TODO(#8210): add reassignments event count
      case TransactionTreeUpdate(tree) =>
        val counts = IngestedEvents.eventCount(Seq(tree))
        counts.numCreatedEvents + counts.numExercisedEvents
      case _ => 0L
    }

    Source
      .fromIterator(() => txs.iterator)
      .batch(ingestionConfig.maxBatchSize.toLong, Vector(_))(_ :+ _)
      .map(batch =>
        batch
          .map(tx =>
            TreeUpdateOrOffsetCheckpoint.Update(tx.update.update, tx.update.synchronizerId)
          )
          .toList
      )
      .zipWithIndex
      .runWith(Sink.foreachAsync(parallelism = 1) { case (batch, index) =>
        logger.info(s"Ingesting batch $index of ${batch.length} elements")
        val batchEventCount = batch.iterator.map(eventsIn).sum
        val wallBefore = System.nanoTime()
        val cpuBefore = getProcessCpuTimeNs
        store.ingestionSink
          .ingestUpdateBatch(NonEmptyList.fromListUnsafe(batch))
          .map { _ =>
            val wallAfter = System.nanoTime()
            val cpuAfter = getProcessCpuTimeNs
            val duration = wallAfter - wallBefore
            val cpuDeltaNs = math.max(cpuAfter - cpuBefore, 0L)
            totalTimeNs += duration
            totalCpuNs += cpuDeltaNs
            val heapNow = getHeapUsedBytes
            maxHeapBytes = maxHeapBytes.max(heapNow)
            totalItems += batch.length
            totalEvents += batchEventCount
            totalBatches += 1
            val avg = totalTimeNs / totalItems
            val avgEvent = if (totalEvents > 0) totalTimeNs / totalEvents else BigDecimal(0)
            val msg =
              f"Ingested batch $index (${batch.length} updates, $batchEventCount events) in $duration ns, average per-update time: $avg%.2f ns, average per-event time: $avgEvent%.2f ns over $totalItems updates ($totalEvents events), total time: $totalTimeNs ns"
            logger.info(msg)
            println(s"${this.getClass.getName}: $msg")
          }
      })
      .map { _ =>
        println(
          f"Process-level metrics: CPU time=${totalCpuNs / 1e6}%.2f ms, peak heap=$maxHeapBytes bytes"
        )

        StoreIngestionPerfMetrics(
          totalItems = totalItems,
          totalEvents = totalEvents,
          totalBatches = totalBatches,
          totalTimeNs = totalTimeNs,
          processCpuTimeNs = totalCpuNs,
          peakHeapBytes = maxHeapBytes,
        )
      }
  }

  /** A separate Python script pushes the metrics to Prometheus Pushgateway.
    * We structure JSON file making the python side mostly readonly, enabling to add new metrics to JSON without changing python script.
    */
  private def writeIngestionMetrics(metrics: StoreIngestionPerfMetrics, success: Boolean): Unit =
    writeMetricsFile(
      StoreIngestionPerfMetrics.toMetricsJson(metrics, success),
      StoreIngestionPerfMetrics.MetricsDir,
      "Ingestion",
    )
}

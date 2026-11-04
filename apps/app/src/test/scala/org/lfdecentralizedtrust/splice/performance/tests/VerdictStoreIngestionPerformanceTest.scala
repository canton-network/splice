package org.lfdecentralizedtrust.splice.performance.tests

import cats.data.NonEmptyList
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{ProcessingTimeout, StorageConfig}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.Config
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.config.{IngestionConfig, SpliceConfig}
import org.lfdecentralizedtrust.splice.http.v0.definitions as httpDefs
import org.lfdecentralizedtrust.splice.scan.config.AnalyzableTimeWindowConfig
import org.lfdecentralizedtrust.splice.scan.rewards.AppActivityComputation
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore.{
  EnvelopeT,
  TrafficSummaryT,
  TransactionViewT,
  VerdictResultDbValue,
  VerdictT,
}
import org.lfdecentralizedtrust.splice.scan.store.db.{DbAppActivityRecordStore, DbScanVerdictStore}
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes.StoreMemberId
import org.lfdecentralizedtrust.splice.store.db.InternedStringStore
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, UpdateHistory}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import io.circe.Json
import java.nio.file.{Files, Path}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/** Ingests verdicts, transaction views and app activity records downloaded from Scan's `/v0/events`,
  * measuring the `insertVerdictsWithAppActivityRecords` write path.
  */
class VerdictStoreIngestionPerformanceTest(
    dsoParty: PartyId,
    migrationId: Long,
    eventsDumpPath: Path,
    storageConfig: StorageConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
    ingestionConfig: IngestionConfig = IngestionConfig(),
)(implicit ec: ExecutionContext, actorSystem: ActorSystem)
    extends BaseStorePerformanceTest(
      eventsDumpPath,
      storageConfig,
      timeouts,
      loggerFactory,
    ) {

  override type Store = DbScanVerdictStore

  /** A verdict from the dump, with its optional traffic summary and app activity records.
    *
    * The dump only has a round when there are app activity records. For verdicts without one,
    * `resolvedRound` reuses the last round seen. This keeps rounds from decreasing within a batch,
    * which `insertVerdictsWithAppActivityRecords` requires.
    */
  private case class LoadedVerdict(
      verdict: httpDefs.EventHistoryVerdict,
      trafficSummary: Option[httpDefs.EventHistoryTrafficSummary],
      appActivityRecords: Option[httpDefs.EventHistoryAppActivityRecords],
      resolvedRound: Option[Long],
  )

  override protected val tablesToSanityCheck: Seq[String] =
    Seq("scan_verdict_store", "scan_verdict_transaction_view_store")

  // Held so run() can initialize the underlying update-history schema.
  private var updateHistoryRef: Option[UpdateHistory] = None

  override protected def mkStore(storage: DbStorage): Future[DbScanVerdictStore] = {
    implicit val initTc: TraceContext = TraceContext.empty
    UpdateHistory
      .create(
        storage = storage,
        domainMigrationId = migrationId,
        storeName = this.getClass.getName,
        participantId = StoreMemberId(mkParticipantId(this.getClass.getSimpleName)),
        updateStreamParty = dsoParty,
        backfillingRequired = UpdateHistory.BackfillingRequirement.BackfillingNotRequired,
        internedStringStore = InternedStringStore.createWithoutWarmup(
          storage,
          10_000L,
          FiniteDuration(1, "hour"),
          loggerFactory,
          NoOpMetricsFactory,
        ),
        analyzableTimeWindowDuration = AnalyzableTimeWindowConfig.UnlimitedAtw,
        loggerFactory = loggerFactory,
        enableImportUpdateBackfill = false,
        metrics = HistoryMetrics.apply(NoOpMetricsFactory, migrationId),
      )
      .map { updateHistory =>
        updateHistoryRef = Some(updateHistory)
        val appActivityStore = new DbAppActivityRecordStore(
          storage,
          updateHistory,
          DbAppActivityRecordStore.IngestionVersions(
            AppActivityComputation.ActivityIngestionCodeVersion,
            0,
          ),
          isFirstSv = false,
          initialRound = 0L,
          loggerFactory,
        )
        DbScanVerdictStore(storage, updateHistory, appActivityStore, loggerFactory)
      }
  }

  def run(): Future[Unit] = withStorage { storage =>
    TraceContext.withNewTraceContext(this.getClass.getName) { implicit tc =>
      for {
        store <- mkStore(storage)
        updateHistory = updateHistoryRef.getOrElse(
          throw new IllegalStateException("UpdateHistory was not initialized by mkStore")
        )
        _ <- updateHistory.ingestionSink.initialize()
        _ <- sanityCheckTables(storage)(count =>
          Option.when(count != 0)(
            s"Expected table to be empty before ingestion, but found $count rows."
          )
        )
        verdicts = loadVerdictsFromDump()
        metrics <- ingestWithMetrics(store, verdicts)
        _ <- sanityCheckTables(storage)(count =>
          Option.when(count == 0)(
            "Expected table to be non-empty after ingestion, but no rows were inserted."
          )
        )
        _ = writeIngestionMetrics(metrics, success = true, names = verdictStoreMetricNames)
      } yield ()
    }
  }

  /** Verdict-store specific metric labels
    */
  private val verdictStoreMetricNames: IngestionMetricNames = IngestionMetricNames(
    avgPrimaryTimeName = "avg_verdict_time_ns",
    avgPrimaryTimeDesc = "Average nanoseconds per ingested verdict",
    totalPrimaryName = "total_verdicts",
    totalPrimaryDesc = "Total number of verdicts written to scan_verdict_store",
    primaryRateName = "verdicts_per_sec",
    primaryRateDesc = "Ingested verdicts per second",
    totalSecondaryName = "total_transaction_views",
    totalSecondaryDesc =
      "Total number of transaction views written to scan_verdict_transaction_view_store",
    secondaryRateName = "transaction_views_per_sec",
    secondaryRateDesc = "Ingested transaction views per second",
  )

  private def loadVerdictsFromDump(): Seq[LoadedVerdict] = {
    val dump = (for {
      json <- io.circe.parser.parse(Files.readString(eventsDumpPath))
      decoded <- httpDefs.EventHistoryResponse.decodeEventHistoryResponse.decodeJson(json)
    } yield decoded)
      .getOrElse(
        throw new IllegalArgumentException(
          s"Failed to parse the events from $eventsDumpPath. It should have the structure of EventHistoryResponse."
        )
      )
    // carrying the last-seen round forward yields a non-decreasing round sequence
    var lastRound: Option[Long] = None
    dump.events.toSeq.flatMap { item =>
      item.verdict.map { v =>
        val roundO = item.appActivityRecords.map(_.roundNumber)
        roundO.foreach(r => lastRound = Some(r))
        LoadedVerdict(v, item.trafficSummary, item.appActivityRecords, roundO.orElse(lastRound))
      }
    }
  }

  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  private def ingestWithMetrics(
      store: DbScanVerdictStore,
      verdicts: Seq[LoadedVerdict],
  )(implicit tc: TraceContext): Future[StoreIngestionPerfMetrics] = {
    var totalTimeNs = BigDecimal(0)
    var totalItems = 0L
    var totalEvents = 0L
    var totalBatches = 0L
    var totalCpuNs = BigDecimal(0)
    var maxHeapBytes = BigDecimal(0)
    var totalVerdicts = 0L
    var totalTransactionViews = 0L
    var totalAppActivityRecords = 0L

    Source
      .fromIterator(() => verdicts.iterator)
      .batch(ingestionConfig.maxBatchSize.toLong, Vector(_))(_ :+ _)
      .map(_.toList)
      .zipWithIndex
      .runWith(Sink.foreachAsync(parallelism = 1) { case (batch, index) =>
        logger.info(s"Ingesting batch $index of ${batch.length} verdicts")
        val items = batch.map(lv => toVerdictRow(lv.verdict, lv.trafficSummary, lv.resolvedRound))
        // App activity records, keyed by the verdict's record time (the store resolves the
        // placeholder verdictRowId to the real row_id during insertion, exactly like production).
        val appActivityRecords = batch.flatMap { lv =>
          lv.appActivityRecords.map { aar =>
            parseTimestamp(lv.verdict.recordTime) -> DbAppActivityRecordStore.AppActivityRecordT(
              verdictRowId = DbAppActivityRecordStore.DUMMY_VERDICT_ROW_ID,
              roundNumber = aar.roundNumber,
              appProviderParties = aar.records.toSeq.map(_.party),
              appActivityWeights = aar.records.toSeq.map(_.weight),
            )
          }
        }
        val hasTrafficSummaries = batch.exists(_.trafficSummary.isDefined)
        // Approximate the round boundaries production obtains via open-mining-round lookups:
        // the earliest round seen in the batch, and no archival info from the dump.
        val firstActiveRoundO = appActivityRecords.map(_._2.roundNumber).minOption
        // prod passes the last archived round, which adds a per-batch meta-row update
        // the dump lacks it, so we approximate it to keep that update in the batch
        val lastArchivedRoundO = batch.flatMap(_.resolvedRound).maxOption.map(_ - 1)
        // Count transaction views as the per-item "work" proxy.
        val batchEventCount = batch.map(_.verdict.transactionViews.views.size.toLong).sum
        // One AppActivityRecordT == one row in app_activity_record_store.
        val batchAppActivityCount = appActivityRecords.size.toLong
        val wallBefore = System.nanoTime()
        val cpuBefore = getProcessCpuTimeNs
        val ingestF = NonEmptyList.fromList(items) match {
          case None => Future.unit
          case Some(itemsNel) =>
            store.insertVerdictsWithAppActivityRecords(
              itemsNel,
              appActivityRecords,
              hasTrafficSummaries = hasTrafficSummaries,
              firstActiveRoundO = firstActiveRoundO,
              lastArchivedRoundO = lastArchivedRoundO,
            )
        }
        ingestF.map { _ =>
          val duration = System.nanoTime() - wallBefore
          totalTimeNs += duration
          totalCpuNs += math.max(getProcessCpuTimeNs - cpuBefore, 0L)
          maxHeapBytes = maxHeapBytes.max(getHeapUsedBytes)
          totalItems += batch.length
          totalEvents += batchEventCount
          totalBatches += 1
          totalVerdicts += batch.length.toLong
          totalTransactionViews += batchEventCount
          totalAppActivityRecords += batchAppActivityCount
          val msg =
            f"Ingested batch $index (${batch.length} verdicts, $batchEventCount views, $batchAppActivityCount app-activity-records) in $duration ns, total $totalItems verdicts in $totalTimeNs ns"
          logger.info(msg)
          println(s"${this.getClass.getName}: $msg")
        }
      })
      .map { _ =>
        def ratePerSec(count: Long): BigDecimal =
          if (totalTimeNs > 0) BigDecimal(count) * BigDecimal(1000000000L) / totalTimeNs
          else BigDecimal(0)
        println(
          f"Process-level metrics: CPU time=${totalCpuNs / 1e6}%.2f ms, peak heap=$maxHeapBytes bytes"
        )
        println(
          f"Ingestion counts: verdicts=$totalVerdicts, transaction-views=$totalTransactionViews, app-activity-records=$totalAppActivityRecords (in $totalTimeNs ns)"
        )
        println(
          f"Ingestion rates (per sec): verdicts=${ratePerSec(totalVerdicts)}%.2f, " +
            f"transaction-views=${ratePerSec(totalTransactionViews)}%.2f, " +
            f"app-activity-records=${ratePerSec(totalAppActivityRecords)}%.2f"
        )
        StoreIngestionPerfMetrics(
          totalItems = totalItems,
          totalEvents = totalEvents,
          totalBatches = totalBatches,
          totalTimeNs = totalTimeNs,
          processCpuTimeNs = totalCpuNs,
          peakHeapBytes = maxHeapBytes,
          totalAppActivityRecords = totalAppActivityRecords,
        )
      }
  }

  /** Map a `/v0/events` verdict to the store's row + transaction views. */
  private def toVerdictRow(
      v: httpDefs.EventHistoryVerdict,
      trafficSummaryO: Option[httpDefs.EventHistoryTrafficSummary],
      roundNumberO: Option[Long],
  ): (VerdictT, Long => Seq[TransactionViewT]) = {
    val recordTime = parseTimestamp(v.recordTime)
    val trafficSummary = trafficSummaryO.map { ts =>
      TrafficSummaryT(
        totalTrafficCost = ts.totalTrafficCost,
        envelopeTrafficSummarys =
          ts.envelopeTrafficSummaries.toSeq.map(e => EnvelopeT(e.trafficCost, e.viewIds.toSeq)),
        sequencingTime = recordTime,
      )
    }
    val row = VerdictT(
      rowId = 0L,
      migrationId = v.migrationId,
      domainId = SynchronizerId.tryFromString(v.domainId),
      recordTime = recordTime,
      finalizationTime = parseTimestamp(v.finalizationTime),
      submittingParticipantUid = v.submittingParticipantUid,
      verdictResult = toVerdictResult(v.verdictResult),
      mediatorGroup = v.mediatorGroup,
      updateId = v.updateId,
      submittingParties = v.submittingParties.toSeq,
      transactionRootViews = v.transactionViews.rootViews.toSeq,
      trafficSummaryO = trafficSummary,
      roundNumber = roundNumberO,
    )
    val mkViews: Long => Seq[TransactionViewT] = rowId =>
      v.transactionViews.views.toSeq.map { view =>
        val confirmingParties = Json.fromValues(view.confirmingParties.map { q =>
          Json.obj(
            "parties" -> Json.fromValues(q.parties.map(Json.fromString)),
            "threshold" -> Json.fromInt(q.threshold),
          )
        })
        TransactionViewT(
          verdictRowId = rowId,
          viewId = view.viewId,
          informees = view.informees.toSeq,
          confirmingParties = confirmingParties,
          subViews = view.subViews.toSeq,
        )
      }
    (row, mkViews)
  }

  private def parseTimestamp(s: String): CantonTimestamp =
    CantonTimestamp.assertFromInstant(java.time.Instant.parse(s))

  private def toVerdictResult(r: httpDefs.VerdictResult): Short = r match {
    case httpDefs.VerdictResult.VerdictResultAccepted => VerdictResultDbValue.Accepted
    case httpDefs.VerdictResult.VerdictResultRejected => VerdictResultDbValue.Rejected
    case _ => VerdictResultDbValue.Unspecified
  }
}

object VerdictStoreIngestionPerformanceTest {

  case class VerdictStoreIngestionPerformanceTestConfig(
      dsoParty: String,
      migrationId: Long,
  )
  private implicit val configReader: ConfigReader[VerdictStoreIngestionPerformanceTestConfig] =
    deriveReader[VerdictStoreIngestionPerformanceTestConfig]

  def tryCreate(eventsDumpPath: Path, config: Config, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): VerdictStoreIngestionPerformanceTest = {
    val spliceConfig = SpliceConfig.loadOrThrow(config)
    val sv1Config = spliceConfig.scanApps.getOrElse(
      InstanceName.tryCreate("sv1Scan"),
      throw new IllegalArgumentException("SV1 config not found"),
    )

    configReader
      .from(config.getValue("performance.tests.VerdictStore"))
      .fold(
        err =>
          throw new IllegalArgumentException(
            s"Failed to read VerdictStore config: $err"
          ),
        cfg =>
          new VerdictStoreIngestionPerformanceTest(
            PartyId.tryFromProtoPrimitive(cfg.dsoParty),
            cfg.migrationId,
            eventsDumpPath,
            sv1Config.storage,
            spliceConfig.parameters.timeouts.processing,
            loggerFactory,
            sv1Config.automation.ingestion,
          ),
      )
  }
}

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
      ingestionConfig,
    ) {

  override type Store = DbScanVerdictStore

  /** A verdict from the event dump together with its optional traffic summary, app activity records
    * and the round number resolved for the verdict row.
    *
    * a round is only exposed via `app_activity_records`.
    * when app_activity_records is missing, we use the last-seen round.
    */
  private case class LoadedVerdict(
      verdict: httpDefs.EventHistoryVerdict,
      trafficSummary: Option[httpDefs.EventHistoryTrafficSummary],
      appActivityRecords: Option[httpDefs.EventHistoryAppActivityRecords],
      resolvedRound: Option[Long],
  )

  override protected val tablesToSanityCheck: Seq[String] =
    Seq("scan_verdict_store", "scan_verdict_transaction_view_store")


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
      .flatMap(updateHistory => updateHistory.ingestionSink.initialize().map(_ => updateHistory))
      .map { updateHistory =>
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
        _ <- sanityCheckTables(storage)(count =>
          Option.when(count != 0)(
            s"Expected table to be empty before ingestion, but found $count rows."
          )
        )
        verdicts = loadVerdictsFromDump()
        _ <- ingestWithMetrics(store, verdicts)
        _ <- sanityCheckTables(storage)(count =>
          Option.when(count == 0)(
            "Expected table to be non-empty after ingestion, but no rows were inserted."
          )
        )
      } yield ()
    }
  }

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
    // carrying the last-seen round forward gives a non-decreasing round sequence
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
  )(implicit tc: TraceContext): Future[Unit] = {
    var totalTimeNs = BigDecimal(0)
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
        // App activity records, keyed by the verdict's record time
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
        val firstActiveRoundO = appActivityRecords.map(_._2.roundNumber).minOption
        val lastArchivedRoundO = Option.empty[Long]
        val batchEventCount = batch.map(_.verdict.transactionViews.views.size.toLong).sum
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
          totalBatches += 1
          totalVerdicts += batch.length.toLong
          totalTransactionViews += batchEventCount
          totalAppActivityRecords += batchAppActivityCount
          val msg =
            f"Ingested batch $index (${batch.length} verdicts, $batchEventCount views, $batchAppActivityCount app-activity-records) in $duration ns, total $totalVerdicts verdicts in $totalTimeNs ns"
          logger.info(msg)
          println(s"${this.getClass.getName}: $msg")
        }
      })
      .map { _ =>
        def ratePerSec(count: Long): BigDecimal =
          if (totalTimeNs > 0) BigDecimal(count) * BigDecimal(1000000000L) / totalTimeNs
          else BigDecimal(0)
        println(
          f"Process-level metrics: CPU time=${totalCpuNs / 1e6}%.2f ms, peak heap=$maxHeapBytes bytes, batches=$totalBatches"
        )
        println(
          f"Ingestion counts: verdicts=$totalVerdicts, transaction-views=$totalTransactionViews, app-activity-records=$totalAppActivityRecords (in $totalTimeNs ns)"
        )
        println(
          f"Ingestion rates (per sec): verdicts=${ratePerSec(totalVerdicts)}%.2f, " +
            f"transaction-views=${ratePerSec(totalTransactionViews)}%.2f, " +
            f"app-activity-records=${ratePerSec(totalAppActivityRecords)}%.2f"
        )
      }
  }

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

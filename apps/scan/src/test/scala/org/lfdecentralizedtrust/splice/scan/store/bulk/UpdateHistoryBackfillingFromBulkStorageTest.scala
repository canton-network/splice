// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.config.{NonNegativeDuration, NonNegativeFiniteDuration}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import io.circe.syntax.*
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItemV2
import org.lfdecentralizedtrust.splice.scan.admin.http.{
  ProtobufJsonScanHttpEncodings,
  ScanHttpEncodings,
  ScanJsonSupport,
}
import org.lfdecentralizedtrust.splice.scan.config.{
  ScanStorageConfig,
  UpdateHistoryBackfillingFromBulkStorageConfig,
}
import org.lfdecentralizedtrust.splice.scan.store.{ScanKeyValueProvider, ScanKeyValueStore}
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdateHistoryBackfillingFromBulkStorage.*
import org.lfdecentralizedtrust.splice.scan.store.historystart.{FakeHistoryReadiness, HistoryStart}
import org.lfdecentralizedtrust.splice.store.{
  HistoryMetrics,
  PageLimit,
  TreeUpdateWithMigrationId,
  UpdateHistory,
  UpdateHistoryTestBase,
}

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.*

class UpdateHistoryBackfillingFromBulkStorageTest extends UpdateHistoryTestBase {

  // Hourly segments, so that a few hours of updates span several segments.
  private val storageConfig = ScanStorageConfig(
    dbAcsSnapshotPeriodHours = 1,
    bulkAcsSnapshotPeriodHours = 1,
    bulkZstdBlockSize = 2000L,
    bulkZstdFrameSize = 10000L,
    bulkMaxFileSize = 25000L,
    zstdCompressionLevel = 3,
  )

  private val base = CantonTimestamp.assertFromInstant(Instant.parse("2026-01-01T00:00:00Z"))
  private def at(hours: Int, minutes: Int): CantonTimestamp =
    base.plusSeconds(hours * 3600L + minutes * 60L)

  // The history start of the destination: the record time of the ACS it imported, in the middle of a segment.
  private val historyStart = at(3, 15)
  // The destination's first ingested update after its history start
  private val firstIngested = at(3, 30)

  private def segment(fromHour: Int, toHour: Int) = UpdatesSegment(at(fromHour, 0), at(toHour, 0))
  private val genesisSegment = UpdatesSegment(CantonTimestamp.MinValue, at(1, 0))

  "UpdateHistoryBackfillingFromBulkStorage" should {

    "load all updates before the first ingested one when the analyzable time window is unlimited" in {
      for {
        data <- setupTestData()
        steps <- data.service().mksrc().runWith(Sink.seq)
        _ <- data.assertDestinationHoldsSourceUpdatesAfter(CantonTimestamp.MinValue)
        loaded <- data.progress.readLastLoadedSegment
      } yield {
        steps shouldBe Seq(
          SegmentLoaded(genesisSegment, 2),
          SegmentLoaded(segment(1, 2), 2),
          SegmentLoaded(segment(2, 3), 2),
          SegmentLoaded(segment(3, 4), 1),
          Loaded(firstIngested),
        )
        loaded shouldBe Some(segment(3, 4))
      }
    }

    "start from the segment containing the start of the analyzable time window" in {
      // The destination ingested up to 04:10, so a window of 150 minutes starts at 01:40.
      for {
        data <- setupTestData(
          destinationAtw = NonNegativeDuration.tryFromDuration(150.minutes)
        )
        steps <- data.service().mksrc().runWith(Sink.seq)
        _ <- data.assertDestinationHoldsSourceUpdatesAfter(at(1, 0))
      } yield steps.headOption shouldBe Some(SegmentLoaded(segment(1, 2), 2))
    }

    "wait for the next segment to be committed" in {
      for {
        data <- setupTestData(committedUpTo = Some(at(2, 0)))
        done = data.service().mksrc().runWith(Sink.seq)
        _ = eventually() {
          data.progress.readLastLoadedSegment.futureValue shouldBe Some(segment(1, 2))
        }
        _ = done.isCompleted shouldBe false
        _ = data.segments.commitUpTo(at(5, 0))
        steps <- done
        _ <- data.assertDestinationHoldsSourceUpdatesAfter(CantonTimestamp.MinValue)
      } yield {
        steps should contain(WaitingForSegment(at(2, 0)))
        steps.lastOption shouldBe Some(Loaded(firstIngested))
      }
    }

    "continue after the last loaded segment when restarted" in {
      for {
        data <- setupTestData(committedUpTo = Some(at(3, 0)))
        firstRun <- data
          .service()
          .mksrc()
          .takeWhile(!_.isInstanceOf[WaitingForSegment])
          .runWith(Sink.seq)
        _ = data.segments.commitUpTo(at(5, 0))
        secondRun <- data.service().mksrc().runWith(Sink.seq)
        _ <- data.assertDestinationHoldsSourceUpdatesAfter(CantonTimestamp.MinValue)
      } yield {
        firstRun.lastOption shouldBe Some(SegmentLoaded(segment(2, 3), 2))
        secondRun shouldBe Seq(SegmentLoaded(segment(3, 4), 1), Loaded(firstIngested))
      }
    }

    "insert nothing twice when a loaded segment is loaded again" in {
      for {
        data <- setupTestData()
        _ <- data.service().mksrc().runWith(Sink.seq)
        // As if the last loaded segment had not been recorded after loading the segments after it
        _ <- data.progress.persistLastLoadedSegment(segment(1, 2))
        steps <- data.service().mksrc().runWith(Sink.seq)
        _ <- data.assertDestinationHoldsSourceUpdatesAfter(CantonTimestamp.MinValue)
      } yield steps.headOption shouldBe Some(SegmentLoaded(segment(2, 3), 0))
    }

    "insert nothing twice when restarted from scratch with another batch size" in {
      for {
        data <- setupTestData()
        _ <- data.service(insertBatchSize = 1).mksrc().runWith(Sink.seq)
        _ <- data.progress.reset
        steps <- data.service(insertBatchSize = 3).mksrc().runWith(Sink.seq)
        _ <- data.assertDestinationHoldsSourceUpdatesAfter(CantonTimestamp.MinValue)
      } yield steps.collect { case SegmentLoaded(_, inserted) => inserted }.sum shouldBe 0
    }

    "skip updates the destination already holds from before its history start" in {
      // The destination also holds the update at 02:59, e.g. from backfilling from its peers, in the middle of a batch.
      for {
        data <- setupTestData(destinationHoldsUpdateBeforeHistoryStart = true)
        steps <- data.service(insertBatchSize = 2).mksrc().runWith(Sink.seq)
        _ <- data.assertDestinationHoldsSourceUpdatesAfter(CantonTimestamp.MinValue)
      } yield steps should contain(SegmentLoaded(segment(2, 3), 1))
    }

    "load up to the first ingested update when it is later than the history start" in {
      // The destination ingested nothing between its history start and 04:10, e.g. after its participant was pruned.
      for {
        data <- setupTestData(destinationIngestsFrom = at(4, 10))
        steps <- data.service().mksrc().runWith(Sink.seq)
        _ <- data.assertDestinationHoldsSourceUpdatesAfter(CantonTimestamp.MinValue)
      } yield steps.takeRight(3) shouldBe Seq(
        SegmentLoaded(segment(3, 4), 2),
        SegmentLoaded(segment(4, 5), 0),
        Loaded(at(4, 10)),
      )
    }

    "wait for the first ingested update after the history start" in {
      for {
        data <- setupTestData(destinationIngestsFrom = at(6, 0))
        steps <- data.service().mksrc().take(2).runWith(Sink.seq)
      } yield steps.distinct shouldBe Seq(
        Waiting(s"this Scan to ingest its first update after its history start $historyStart")
      )
    }

    "load nothing when the history starts at genesis" in {
      for {
        data <- setupTestData(start = Some(HistoryStart.Genesis))
        steps <- data.service().mksrc().runWith(Sink.seq)
        updates <- data.destination.getUpdatesWithoutImportUpdates(None, PageLimit.tryCreate(1000))
      } yield {
        steps shouldBe Seq(NothingToLoad)
        updates.map(_.update.update.recordTime) shouldBe Seq(at(3, 30), at(4, 10))
      }
    }

    "wait for the history start to be determined" in {
      for {
        data <- setupTestData(start = None)
        steps <- data.service().mksrc().take(2).runWith(Sink.seq)
      } yield steps.distinct shouldBe Seq(
        Waiting("the history start of this Scan to be determined")
      )
    }
  }

  "notStartedBecause" should {
    "start only when enabled and backfilling from peers is off" in {
      notStartedBecause(enabled = false, backfillingFromPeersEnabled = false) shouldBe Some(
        NotStarted.Disabled
      )
      notStartedBecause(enabled = false, backfillingFromPeersEnabled = true) shouldBe Some(
        NotStarted.Disabled
      )
      notStartedBecause(enabled = true, backfillingFromPeersEnabled = true) shouldBe Some(
        NotStarted.BackfillingFromPeers
      )
      notStartedBecause(enabled = true, backfillingFromPeersEnabled = false) shouldBe None
    }
  }

  "UpdateHistoryBackfillingFromBulkStorageConfig" should {
    "reject values that would stop or spin the loading" in {
      an[IllegalArgumentException] should be thrownBy UpdateHistoryBackfillingFromBulkStorageConfig(
        insertBatchSize = 0
      )
      an[IllegalArgumentException] should be thrownBy UpdateHistoryBackfillingFromBulkStorageConfig(
        maxLineBytes = 0
      )
      an[IllegalArgumentException] should be thrownBy UpdateHistoryBackfillingFromBulkStorageConfig(
        pollingInterval = NonNegativeFiniteDuration.Zero
      )
    }
  }

  "sameMigrationRuns" should {
    "split a batch where the migration id changes, keeping the order" in {
      def update(migrationId: Long, contract: Int) =
        TreeUpdateWithMigrationId(
          UpdateHistory.UpdateHistoryResponse(
            org.lfdecentralizedtrust.splice.environment.ledger.api.TransactionTreeUpdate(
              mkCreateTx(
                1,
                Seq(appRewardCoupon(0, party1, contractId = validContractId(contract))),
                defaultEffectiveAt,
                Seq(party1),
                domain1,
                "",
              )
            ),
            domain1,
          ),
          migrationId,
        )
      val batch = Seq(update(1, 1), update(1, 2), update(2, 3), update(1, 4))
      sameMigrationRuns(batch).map(_.map(_.migrationId)) shouldBe Seq(
        Vector(1L, 1L),
        Vector(2L),
        Vector(1L),
      )
    }
  }

  /** What [[UpdateHistorySegmentBulkStorage]] writes for an update, in the lossless encoding. */
  private def encode(update: TreeUpdateWithMigrationId): String = {
    import ScanJsonSupport.*
    ScanHttpEncodings
      .encodeUpdateV2(
        update,
        ScanStorageConfig.Encoding.ProtobufJson.damlValueEncoding,
        ScanHttpEncodings.V1,
      )
      .asJson
      .noSpacesSortKeys
  }

  /** The committed segments of the source history, as bulk storage would hold them: the segments that have updates,
    * on the grid of `storageConfig`, each update encoded and decoded as when it is read from bulk storage.
    */
  private class SourceSegments(
      source: UpdateHistory,
      initiallyCommittedUpTo: Option[CantonTimestamp],
  ) extends CommittedSegments {
    @volatile private var committed: Option[CantonTimestamp] = initiallyCommittedUpTo

    def commitUpTo(upTo: CantonTimestamp): Unit = committed = Some(upTo)

    private def sourceUpdates: Future[Seq[TreeUpdateWithMigrationId]] =
      source.getUpdatesWithoutImportUpdates(None, PageLimit.tryCreate(1000))

    private def segmentOf(recordTime: CantonTimestamp, first: CantonTimestamp): UpdatesSegment = {
      val genesisEnd = storageConfig.computeBulkSnapshotTimeAfter(first)
      if (recordTime <= genesisEnd) UpdatesSegment(CantonTimestamp.MinValue, genesisEnd)
      else {
        val to = storageConfig.computeBulkSnapshotTimeAfter(recordTime.minusSeconds(1))
        UpdatesSegment(to.minusSeconds(3600), to)
      }
    }

    override def firstSegmentAfter(after: CantonTimestamp)(implicit
        tc: TraceContext
    ): Future[Option[UpdatesSegment]] =
      sourceUpdates.map { updates =>
        val recordTimes = updates.map(_.update.update.recordTime)
        recordTimes.headOption.flatMap { first =>
          recordTimes
            .map(segmentOf(_, first))
            .distinct
            .filter(s => s.toTimestamp > after && committed.exists(_ >= s.toTimestamp))
            .minByOption(_.fromTimestamp)
        }
      }

    override def committedUpTo(implicit tc: TraceContext): Future[Option[CantonTimestamp]] =
      Future.successful(committed)

    override def updates(segment: UpdatesSegment)(implicit
        tc: TraceContext
    ): Source[TreeUpdateWithMigrationId, NotUsed] =
      Source.future(sourceUpdates).mapConcat { updates =>
        updates
          .filter { u =>
            val recordTime = u.update.update.recordTime
            recordTime > segment.fromTimestamp && recordTime <= segment.toTimestamp
          }
          .map(u =>
            ProtobufJsonScanHttpEncodings.httpToLapiUpdate(
              io.circe.parser
                .decode[UpdateHistoryItemV2](encode(u))
                .fold(err => throw err, identity)
            )
          )
      }
  }

  private case class TestData(
      source: UpdateHistory,
      destination: UpdateHistory,
      segments: SourceSegments,
      progress: Progress,
      start: Option[HistoryStart],
  ) {
    def service(insertBatchSize: Int = 500): UpdateHistoryBackfillingFromBulkStorage =
      new UpdateHistoryBackfillingFromBulkStorage(
        UpdateHistoryBackfillingFromBulkStorageConfig(
          enabled = true,
          pollingInterval = NonNegativeFiniteDuration.ofMillis(100),
          insertBatchSize = insertBatchSize,
        ),
        storageConfig,
        destination,
        segments,
        new FakeHistoryReadiness(start = start),
        progress,
        HistoryMetrics(NoOpMetricsFactory, migration1).BulkStorage.updateHistoryLoadedUpdates,
        loggerFactory,
      )

    /** The destination holds the source's updates with record times after `after`, and nothing else. */
    def assertDestinationHoldsSourceUpdatesAfter(after: CantonTimestamp) =
      for {
        sourceUpdates <- source.getUpdatesWithoutImportUpdates(None, PageLimit.tryCreate(1000))
        destinationUpdates <- destination.getUpdatesWithoutImportUpdates(
          None,
          PageLimit.tryCreate(1000),
        )
      } yield destinationUpdates.map(encode) shouldBe sourceUpdates
        .filter(_.update.update.recordTime > after)
        .map(encode)
  }

  /** The source holds updates from 00:10 on. The destination joins at 03:15 and ingests from then on, so its
    * segments are: genesis (00:10, 00:40), 01:00-02:00 (an unassignment and an assignment), 02:00-03:00 (two
    * creates), 03:00-04:00 (a create at the history start, then the destination's first own updates at 03:30).
    *
    * @param destinationIngestsFrom the destination ingests the updates from this record time on
    * @param destinationHoldsUpdateBeforeHistoryStart the destination also holds the update at 02:59
    */
  private def setupTestData(
      start: Option[HistoryStart] = Some(HistoryStart.From(historyStart)),
      committedUpTo: Option[CantonTimestamp] = Some(at(5, 0)),
      destinationAtw: NonNegativeDuration =
        NonNegativeDuration.tryFromDuration(scala.concurrent.duration.Duration.Inf),
      destinationIngestsFrom: CantonTimestamp = firstIngested,
      destinationHoldsUpdateBeforeHistoryStart: Boolean = false,
  ): Future[TestData] = {
    val source = mkStore(participantId = participant1)
    val destination =
      mkStore(participantId = participant2, analyzableTimeWindowDuration = destinationAtw)
    def createOnDestinationToo(i: Int, recordTime: CantonTimestamp) =
      createMulti(
        domain1,
        validContractId(i),
        validOffset(i),
        party1,
        Seq(source, destination),
        recordTime,
      )
    def createLive(i: Int, recordTime: CantonTimestamp) =
      if (recordTime >= destinationIngestsFrom) createOnDestinationToo(i, recordTime)
      else create(domain1, validContractId(i), validOffset(i), party1, source, recordTime)
    for {
      _ <- initStore(source)
      _ <- initStore(destination)
      _ <- create(domain1, validContractId(1), validOffset(1), party1, source, at(0, 10))
      _ <- create(domain1, validContractId(2), validOffset(2), party1, source, at(0, 40))
      _ <- unassign(
        domain1,
        domain2,
        validContractId(2),
        validOffset(3),
        party1,
        0,
        "rid",
        source,
        at(1, 20),
      )
      _ <- assign(
        domain2,
        domain1,
        validContractId(2),
        validOffset(4),
        party1,
        0,
        "rid",
        source,
        at(1, 50),
      )
      _ <- create(domain1, validContractId(3), validOffset(5), party1, source, at(2, 30))
      _ <-
        if (destinationHoldsUpdateBeforeHistoryStart) createOnDestinationToo(6, at(2, 59))
        else create(domain1, validContractId(6), validOffset(6), party1, source, at(2, 59))
      _ <- create(domain1, validContractId(7), validOffset(7), party1, source, historyStart)
      _ <- createLive(8, firstIngested)
      _ <- createLive(9, at(4, 10))
      kvProvider <- ScanKeyValueStore(
        dsoParty = dsoParty,
        participantId = participant2,
        storage,
        loggerFactory,
      ).map(new ScanKeyValueProvider(_, loggerFactory))
    } yield TestData(
      source,
      destination,
      new SourceSegments(source, committedUpTo),
      new Progress(
        kvProvider,
        HistoryMetrics(NoOpMetricsFactory, migration1).BulkStorage.updateHistoryLoadedUpTo,
        loggerFactory,
      ),
      start,
    )
  }
}

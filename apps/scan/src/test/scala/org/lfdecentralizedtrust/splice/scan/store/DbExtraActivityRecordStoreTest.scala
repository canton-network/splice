// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.daml.metrics.api.noop.NoOpMetricsFactory
import org.lfdecentralizedtrust.splice.scan.config.AnalyzableTimeWindowConfig
import org.lfdecentralizedtrust.splice.scan.store.AppActivityStore.RoundIngestionStatus
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbExtraActivityRecordStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbExtraActivityRecordStore.*
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, StoreTestBase, UpdateHistory}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, SplicePostgresTest}

import scala.concurrent.Future

class DbExtraActivityRecordStoreTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest {

  private val migrationId = 0L

  "DbExtraActivityRecordStore" should {

    "round-trip extra activity records through insert + read" in {
      for {
        store <- newStore()
        records = Seq(
          mkRecord(roundNumber = 10L, recordTime = 100L, appProvider = "app1", appWeight = 100L),
          mkRecord(
            roundNumber = 10L,
            recordTime = 101L,
            appProvider = "app2",
            appWeight = 50L,
            assetProvider = Some("asset1"),
            assetWeight = Some(25L),
          ),
          mkRecord(roundNumber = 11L, recordTime = 200L, appProvider = "app1", appWeight = 75L),
        )
        _ <- store.insertExtraActivityRecordsForTesting(records)
        loaded <- store.getRecordsForTesting()
      } yield {
        loaded shouldBe records
      }
    }

    "insert nothing on an empty batch" in {
      for {
        store <- newStore()
        _ <- store.insertExtraActivityRecordsForTesting(Seq.empty)
        loaded <- store.getRecordsForTesting()
      } yield {
        loaded shouldBe empty
      }
    }

    "bootstrap the meta row via startAndEnsureMeta with the PoC earliestIngestedRound stub" in {
      val startedAt = 123456L
      val initialRound = 7L
      for {
        store <- newStore(initialRound = initialRound)
        _ <- store.startAndEnsureMeta(startedAt)
        loaded <- store.lookupExtraActivityRecordMeta(1, 0)
      } yield {
        val meta = loaded.value
        meta.codeVersion shouldBe 1
        meta.userVersion shouldBe 0
        meta.startedIngestingAt shouldBe startedAt
        meta.earliestIngestedRound shouldBe initialRound - 1
        meta.lastArchivedRound shouldBe None
      }
    }

    "report startedIngestingAt and earliestIngestedRound from the meta row" in {
      for {
        store <- newStore(initialRound = 7L)
        _ <- store.startAndEnsureMeta(startedIngestingAt = 999L)
        startedAt <- store.startedIngestingAt
        earliestIngested <- store.earliestIngestedRound()
      } yield {
        startedAt shouldBe Some(999L)
        earliestIngested shouldBe Some(6L)
      }
    }

    "return None for all meta-row queries when no meta row has been bootstrapped" in {
      for {
        store <- newStore()
        startedAt <- store.startedIngestingAt
        earliestIngested <- store.earliestIngestedRound()
        earliestComplete <- store.earliestRoundWithCompleteAppActivity()
        latestComplete <- store.latestRoundWithCompleteAppActivity()
      } yield {
        startedAt shouldBe None
        earliestIngested shouldBe None
        earliestComplete shouldBe None
        latestComplete shouldBe None
      }
    }

    "advance latestRoundWithCompleteAppActivity when last_archived_round is bumped" in {
      for {
        store <- newStore(initialRound = 7L)
        _ <- store.startAndEnsureMeta(startedIngestingAt = 100L)
        beforeBump <- store.latestRoundWithCompleteAppActivity()
        _ <- store.bumpLastArchivedRoundForTesting(round = 12L)
        afterBump <- store.latestRoundWithCompleteAppActivity()
        reloaded <- store.lookupExtraActivityRecordMeta(1, 0)
      } yield {
        beforeBump shouldBe None
        afterBump shouldBe Some(12L)
        reloaded.value.lastArchivedRound shouldBe Some(12L)
      }
    }

    "return earliest_ingested_round + 1 for earliestRoundWithCompleteAppActivity once archival catches up" in {
      // initialRound 7 => earliestIngested 6, so earliestComplete is 7 as soon as
      // last_archived_round >= 7.
      for {
        store <- newStore(initialRound = 7L)
        _ <- store.startAndEnsureMeta(startedIngestingAt = 100L)
        beforeArchive <- store.earliestRoundWithCompleteAppActivity()
        _ <- store.bumpLastArchivedRoundForTesting(round = 6L)
        onBoundary <- store.earliestRoundWithCompleteAppActivity()
        _ <- store.bumpLastArchivedRoundForTesting(round = 7L)
        pastBoundary <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        beforeArchive shouldBe None
        onBoundary shouldBe None
        pastBoundary shouldBe Some(7L)
      }
    }

    "report Undetermined for a round beyond the earliest ingested boundary" in {
      for {
        store <- newStore(initialRound = 7L)
        _ <- store.startAndEnsureMeta(startedIngestingAt = 100L)
        status <- store.ingestionStatusForRound(10L)
      } yield {
        status shouldBe RoundIngestionStatus.Undetermined
      }
    }

    "report CannotProvide for a round at or below earliest_ingested_round" in {
      for {
        store <- newStore(initialRound = 7L)
        _ <- store.startAndEnsureMeta(startedIngestingAt = 100L)
        status <- store.ingestionStatusForRound(6L)
      } yield {
        status shouldBe RoundIngestionStatus.CannotProvide
      }
    }

    "report CannotProvide for a late-joining non-first SV with no meta row" in {
      for {
        store <- newStore(isFirstSv = false)
        status <- store.ingestionStatusForRound(5L)
      } yield {
        status shouldBe RoundIngestionStatus.CannotProvide
      }
    }

    "report Undetermined for a first SV in its initial-ingestion window with no meta row" in {
      for {
        store <- newStore(isFirstSv = true)
        status <- store.ingestionStatusForRound(5L)
      } yield {
        status shouldBe RoundIngestionStatus.Undetermined
      }
    }
  }

  private def mkRecord(
      roundNumber: Long,
      recordTime: Long,
      appProvider: String,
      appWeight: Long,
      assetProvider: Option[String] = None,
      assetWeight: Option[Long] = None,
  ): ExtraActivityRecordT =
    ExtraActivityRecordT(
      roundNumber = roundNumber,
      recordTime = recordTime,
      appProviderParties = Seq(appProvider),
      appActivityWeights = Seq(appWeight),
      assetActivityProviderParties = assetProvider.toList,
      assetActivityWeights = assetWeight.toList,
    )

  private val storeCounter = new java.util.concurrent.atomic.AtomicLong(1)

  private def newStore(
      versions: DbAppActivityRecordStore.IngestionVersions =
        DbAppActivityRecordStore.IngestionVersions(1, 0),
      isFirstSv: Boolean = false,
      initialRound: Long = 0L,
  ): Future[DbExtraActivityRecordStore] = {
    val n = storeCounter.getAndIncrement()
    val participantId = mkParticipantId(s"extra-activity-test-$n")
    for {
      updateHistory <- UpdateHistory.create(
        storage,
        migrationId,
        s"extra_activity_test_$n",
        AcsJdbcTypes.StoreMemberId(participantId),
        dsoParty,
        BackfillingRequirement.BackfillingNotRequired,
        internedStringStore(storage),
        analyzableTimeWindowDuration = AnalyzableTimeWindowConfig.UnlimitedAtw,
        loggerFactory,
        enableImportUpdateBackfill = false,
        HistoryMetrics(NoOpMetricsFactory, migrationId),
      )
      _ <- updateHistory.ingestionSink.initialize()
    } yield new DbExtraActivityRecordStore(
      storage,
      updateHistory,
      versions,
      isFirstSv,
      initialRound,
      loggerFactory,
    )
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}

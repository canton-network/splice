// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.daml.metrics.api.noop.NoOpMetricsFactory
import org.lfdecentralizedtrust.splice.scan.config.AnalyzableTimeWindowConfig
import org.lfdecentralizedtrust.splice.scan.store.ActivityRecordMetaStore.RoundIngestionStatus
import org.lfdecentralizedtrust.splice.scan.store.db.{
  DbActivityRecordMetaStore,
  DbAppActivityRecordStore,
  DbExtraActivityRecordStore,
}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, StoreTestBase, UpdateHistory}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, SplicePostgresTest}

import scala.concurrent.Future

class DbActivityRecordMetaStoreTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest {

  private val migrationId = 0L

  "DbActivityRecordMetaStore" should {

    "return None for all round-completion queries when neither underlying store has a meta row" in {
      for {
        (facade, _, _) <- newStores()
        startedAt <- facade.startedIngestingAt
        earliestComplete <- facade.earliestRoundWithCompleteAppActivity()
        latestComplete <- facade.latestRoundWithCompleteAppActivity()
      } yield {
        startedAt shouldBe None
        earliestComplete shouldBe None
        latestComplete shouldBe None
      }
    }

    "bound earliestRoundWithCompleteAppActivity by max of the two underlying stores" in {
      for {
        (facade, appStore, extraStore) <- newStores(initialRound = 5L)
        // Seed both meta rows and advance last_archived_round past
        // earliest_ingested_round + 1 for both. App-side earliest-complete: 5.
        // Extra-side earliest-complete: 7.
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(5L))
        _ <- extraStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 6L, Some(7L))
        combined <- facade.earliestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe Some(7L)
      }
    }

    "return None for earliestRoundWithCompleteAppActivity when only one store has reached completeness" in {
      for {
        (facade, appStore, _) <- newStores(initialRound = 5L)
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(5L))
        combined <- facade.earliestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe None
      }
    }

    "bound latestRoundWithCompleteAppActivity by min of the two underlying stores" in {
      for {
        (facade, appStore, extraStore) <- newStores(initialRound = 5L)
        // App-side latest-complete: 10. Extra-side latest-complete: 8.
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(10L))
        _ <- extraStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, Some(8L))
        combined <- facade.latestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe Some(8L)
      }
    }

    "return None for latestRoundWithCompleteAppActivity when only one store has a last_archived_round" in {
      for {
        (facade, appStore, extraStore) <- newStores(initialRound = 5L)
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(10L))
        // Extra-side meta present, but no last_archived_round yet.
        _ <- extraStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, None)
        combined <- facade.latestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe None
      }
    }

    "report CannotProvide for ingestionStatusForRound when either underlying store does" in {
      for {
        (facade, appStore, extraStore) <- newStores(initialRound = 5L)
        // Both meta rows present with earliest_ingested_round = 4, so
        // app-side alone would report CannotProvide for round 4,
        // Undetermined for round 20. Combine both and ensure app's
        // CannotProvide dominates.
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
        _ <- extraStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, None)
        atEarliest <- facade.ingestionStatusForRound(4L)
        beyond <- facade.ingestionStatusForRound(20L)
      } yield {
        atEarliest shouldBe RoundIngestionStatus.CannotProvide
        beyond shouldBe RoundIngestionStatus.Undetermined
      }
    }

    "report CannotProvide for ingestionStatusForRound when the extra store is behind" in {
      for {
        (facade, appStore, extraStore) <- newStores(initialRound = 5L)
        // App-side earliest-ingested 4 (so round 20 looks Undetermined
        // on the app side alone). Extra-side earliest-ingested 25 (round
        // 20 is BELOW it → CannotProvide on the extra side). The combined
        // response must adopt extra's CannotProvide.
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
        _ <- extraStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 25L, None)
        combined <- facade.ingestionStatusForRound(20L)
      } yield {
        combined shouldBe RoundIngestionStatus.CannotProvide
      }
    }

    "report Undetermined for ingestionStatusForRound when neither underlying store can place the round below its earliest ingested" in {
      for {
        (facade, appStore, extraStore) <- newStores(initialRound = 5L)
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
        _ <- extraStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, None)
        combined <- facade.ingestionStatusForRound(20L)
      } yield {
        combined shouldBe RoundIngestionStatus.Undetermined
      }
    }

    "take the min of startedIngestingAt when both stores have a value" in {
      for {
        (facade, appStore, extraStore) <- newStores(initialRound = 5L)
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 1000L, 4L, None)
        _ <- extraStore.insertExtraActivityRecordMetaForTesting(1, 0, 500L, 4L, None)
        combined <- facade.startedIngestingAt
      } yield {
        combined shouldBe Some(500L)
      }
    }

    "fall back to the single non-None startedIngestingAt when only one store has a meta row" in {
      for {
        (facade, appStore, _) <- newStores(initialRound = 5L)
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 1000L, 4L, None)
        combined <- facade.startedIngestingAt
      } yield {
        combined shouldBe Some(1000L)
      }
    }
  }

  private val storeCounter = new java.util.concurrent.atomic.AtomicLong(1)

  private def newStores(
      versions: DbAppActivityRecordStore.IngestionVersions =
        DbAppActivityRecordStore.IngestionVersions(1, 0),
      isFirstSv: Boolean = false,
      initialRound: Long = 0L,
  ): Future[(DbActivityRecordMetaStore, DbAppActivityRecordStore, DbExtraActivityRecordStore)] = {
    val n = storeCounter.getAndIncrement()
    val participantId = mkParticipantId(s"activity-meta-test-$n")
    for {
      updateHistory <- UpdateHistory.create(
        storage,
        migrationId,
        s"activity_meta_test_$n",
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
    } yield {
      val appStore = new DbAppActivityRecordStore(
        storage,
        updateHistory,
        versions,
        isFirstSv,
        initialRound,
        loggerFactory,
      )
      val extraStore = new DbExtraActivityRecordStore(
        storage,
        updateHistory,
        versions,
        isFirstSv,
        initialRound,
        loggerFactory,
      )
      val facade = new DbActivityRecordMetaStore(appStore, extraStore, loggerFactory)
      (facade, appStore, extraStore)
    }
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}

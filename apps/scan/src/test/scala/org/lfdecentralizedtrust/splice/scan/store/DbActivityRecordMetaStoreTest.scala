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

    // ============ Combine semantics ============

    "return None for all round-completion queries when neither side has a meta row" in {
      for {
        (facade, _) <- newStores()
        startedAt <- facade.startedIngestingAt
        earliestComplete <- facade.earliestRoundWithCompleteAppActivity()
        latestComplete <- facade.latestRoundWithCompleteAppActivity()
      } yield {
        startedAt shouldBe None
        earliestComplete shouldBe None
        latestComplete shouldBe None
      }
    }

    "bound earliestRoundWithCompleteAppActivity by max of the two sides" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        // Seed both meta rows and advance last_archived_round past
        // earliest_ingested_round + 1 for both. App-side earliest-complete: 5.
        // Extra-side earliest-complete: 7.
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(5L))
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 6L, Some(7L))
        combined <- facade.earliestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe Some(7L)
      }
    }

    "return None for earliestRoundWithCompleteAppActivity when only one side has reached completeness" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(5L))
        combined <- facade.earliestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe None
      }
    }

    "bound latestRoundWithCompleteAppActivity by min of the two sides" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        // App-side latest-complete: 10. Extra-side latest-complete: 8.
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(10L))
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, Some(8L))
        combined <- facade.latestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe Some(8L)
      }
    }

    "return None for latestRoundWithCompleteAppActivity when only one side has a last_archived_round" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(10L))
        // Extra-side meta present, but no last_archived_round yet.
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, None)
        combined <- facade.latestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe None
      }
    }

    "report CannotProvide for ingestionStatusForRound when either side does" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        // Both meta rows present with earliest_ingested_round = 4, so
        // each side alone reports CannotProvide for round 4 and
        // Undetermined for round 20. The combined result must agree.
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, None)
        atEarliest <- facade.ingestionStatusForRound(4L)
        beyond <- facade.ingestionStatusForRound(20L)
      } yield {
        atEarliest shouldBe RoundIngestionStatus.CannotProvide
        beyond shouldBe RoundIngestionStatus.Undetermined
      }
    }

    "report CannotProvide for ingestionStatusForRound when the extra side is behind" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        // App-side earliest-ingested 4 (so round 20 looks Undetermined
        // on the app side alone). Extra-side earliest-ingested 25 (round
        // 20 is BELOW it → CannotProvide on the extra side). The combined
        // response must adopt extra's CannotProvide.
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 25L, None)
        combined <- facade.ingestionStatusForRound(20L)
      } yield {
        combined shouldBe RoundIngestionStatus.CannotProvide
      }
    }

    "report Undetermined for ingestionStatusForRound when neither side places the round below its earliest ingested" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, None)
        combined <- facade.ingestionStatusForRound(20L)
      } yield {
        combined shouldBe RoundIngestionStatus.Undetermined
      }
    }

    "take the min of startedIngestingAt when both sides have a value" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 1000L, 4L, None)
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 500L, 4L, None)
        combined <- facade.startedIngestingAt
      } yield {
        combined shouldBe Some(500L)
      }
    }

    "fall back to the single non-None startedIngestingAt when only one side has a meta row" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        _ <- appStore.insertActivityRecordMetaForTesting(1, 0, 1000L, 4L, None)
        combined <- facade.startedIngestingAt
      } yield {
        combined shouldBe Some(1000L)
      }
    }

    // ============ Extra-side meta ownership ============

    "bootstrap the extra-side meta row via startAndEnsureExtraMeta with the PoC earliestIngestedRound stub" in {
      val startedAt = 123456L
      val initialRound = 7L
      for {
        (facade, _) <- newStores(initialRound = initialRound)
        _ <- facade.startAndEnsureExtraMeta(startedAt)
        loaded <- facade.lookupExtraActivityRecordMeta(1, 0)
      } yield {
        val meta = loaded.value
        meta.codeVersion shouldBe 1
        meta.userVersion shouldBe 0
        meta.startedIngestingAt shouldBe startedAt
        meta.earliestIngestedRound shouldBe initialRound - 1
        meta.lastArchivedRound shouldBe None
      }
    }

    "round-trip a seeded extra-side meta row via insertExtraActivityRecordMetaForTesting + lookup" in {
      for {
        (facade, _) <- newStores(initialRound = 5L)
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 999L, 4L, Some(10L))
        loaded <- facade.lookupExtraActivityRecordMeta(1, 0)
      } yield {
        val meta = loaded.value
        meta.codeVersion shouldBe 1
        meta.userVersion shouldBe 0
        meta.startedIngestingAt shouldBe 999L
        meta.earliestIngestedRound shouldBe 4L
        meta.lastArchivedRound shouldBe Some(10L)
      }
    }

    "advance last_archived_round via bumpExtraLastArchivedRoundForTesting" in {
      for {
        (facade, _) <- newStores(initialRound = 7L)
        _ <- facade.startAndEnsureExtraMeta(startedIngestingAt = 100L)
        reloadedBefore <- facade.lookupExtraActivityRecordMeta(1, 0)
        _ <- facade.bumpExtraLastArchivedRoundForTesting(round = 12L)
        reloadedAfter <- facade.lookupExtraActivityRecordMeta(1, 0)
      } yield {
        reloadedBefore.value.lastArchivedRound shouldBe None
        reloadedAfter.value.lastArchivedRound shouldBe Some(12L)
      }
    }
  }

  private val storeCounter = new java.util.concurrent.atomic.AtomicLong(1)

  private def newStores(
      versions: DbAppActivityRecordStore.IngestionVersions =
        DbAppActivityRecordStore.IngestionVersions(1, 0),
      isFirstSv: Boolean = false,
      initialRound: Long = 0L,
  ): Future[(DbActivityRecordMetaStore, DbAppActivityRecordStore)] = {
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
      val facade = new DbActivityRecordMetaStore(
        storage,
        updateHistory,
        versions,
        isFirstSv,
        initialRound,
        loggerFactory,
      )
      (facade, appStore)
    }
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}

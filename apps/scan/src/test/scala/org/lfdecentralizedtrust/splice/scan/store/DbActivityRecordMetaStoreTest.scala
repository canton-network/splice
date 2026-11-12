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
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

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
        _ <- facade.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(5L))
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 6L, Some(7L))
        combined <- facade.earliestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe Some(7L)
      }
    }

    "return None for earliestRoundWithCompleteAppActivity when only one side has reached completeness" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        _ <- facade.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(5L))
        combined <- facade.earliestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe None
      }
    }

    "bound latestRoundWithCompleteAppActivity by min of the two sides" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        // App-side latest-complete: 10. Extra-side latest-complete: 8.
        _ <- facade.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(10L))
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, Some(8L))
        combined <- facade.latestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe Some(8L)
      }
    }

    "return None for latestRoundWithCompleteAppActivity when only one side has a last_archived_round" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        _ <- facade.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(10L))
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
        _ <- facade.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
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
        _ <- facade.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 25L, None)
        combined <- facade.ingestionStatusForRound(20L)
      } yield {
        combined shouldBe RoundIngestionStatus.CannotProvide
      }
    }

    "report Undetermined for ingestionStatusForRound when neither side places the round below its earliest ingested" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        _ <- facade.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, None)
        combined <- facade.ingestionStatusForRound(20L)
      } yield {
        combined shouldBe RoundIngestionStatus.Undetermined
      }
    }

    "take the min of startedIngestingAt when both sides have a value" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        _ <- facade.insertAppActivityRecordMetaForTesting(1, 0, 1000L, 4L, None)
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 500L, 4L, None)
        combined <- facade.startedIngestingAt
      } yield {
        combined shouldBe Some(500L)
      }
    }

    "fall back to the single non-None startedIngestingAt when only one side has a meta row" in {
      for {
        (facade, appStore) <- newStores(initialRound = 5L)
        _ <- facade.insertAppActivityRecordMetaForTesting(1, 0, 1000L, 4L, None)
        combined <- facade.startedIngestingAt
      } yield {
        combined shouldBe Some(1000L)
      }
    }

    // ============ Extra-side meta ownership ============

    "insert a new extra-side meta row on first ensureExtraMetaDBIO" in {
      val startedAt = 123456L
      val earliestRound = 6L
      for {
        (facade, _) <- newStores(initialRound = 7L)
        result <- runEnsureExtraMeta(facade, (startedAt, earliestRound))
        loaded <- facade.lookupExtraActivityRecordMeta(1, 0)
      } yield {
        result shouldBe DbActivityRecordMetaStore.InsertMeta
        val meta = loaded.value
        meta.codeVersion shouldBe 1
        meta.userVersion shouldBe 0
        meta.startedIngestingAt shouldBe startedAt
        meta.earliestIngestedRound shouldBe earliestRound
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
        _ <- facade.insertExtraActivityRecordMetaForTesting(1, 0, 100L, 6L, None)
        reloadedBefore <- facade.lookupExtraActivityRecordMeta(1, 0)
        _ <- facade.bumpExtraLastArchivedRoundForTesting(round = 12L)
        reloadedAfter <- facade.lookupExtraActivityRecordMeta(1, 0)
      } yield {
        reloadedBefore.value.lastArchivedRound shouldBe None
        reloadedAfter.value.lastArchivedRound shouldBe Some(12L)
      }
    }
  }

  // ============ Per-side meta semantics ============
  // Each test runs twice — once against appMeta, once against extraMeta —
  // to pin that MetaReader's SQL template is correct regardless of table.

  "earliestRoundWithCompleteAppActivity on either side" should {
    sides.foreach { case (sideName, pickSide) =>
      s"[$sideName] return None when no meta record exists" in {
        for {
          (facade, _) <- newStores()
          side = pickSide(facade)
          result <- side.reader.earliestRoundWithCompleteAppActivity()
        } yield result shouldBe None
      }

      s"[$sideName] return None when last_archived_round is not set" in {
        for {
          (facade, _) <- newStores()
          side = pickSide(facade)
          _ <- side.insertMeta(1, 0, 1000L, 0L, None)
          result <- side.reader.earliestRoundWithCompleteAppActivity()
        } yield result shouldBe None
      }

      s"[$sideName] return None while only the first ingested round has been archived" in {
        for {
          (facade, _) <- newStores()
          side = pickSide(facade)
          _ <- side.insertMeta(1, 0, 1000L, 42L, Some(42L))
          result <- side.reader.earliestRoundWithCompleteAppActivity()
        } yield result shouldBe None
      }

      s"[$sideName] return the second ingested round once it has been archived" in {
        for {
          (facade, _) <- newStores()
          side = pickSide(facade)
          _ <- side.insertMeta(1, 0, 1000L, 42L, Some(43L))
          result <- side.reader.earliestRoundWithCompleteAppActivity()
        } yield result.value shouldBe 43L
      }

      s"[$sideName] use current meta row when multiple meta rows exist" in {
        for {
          (facade, _) <- newStores()
          side = pickSide(facade)
          _ <- side.insertMeta(0, 0, 1000L, 10L, Some(15L))
          _ <- side.insertMeta(1, 0, 2000L, 20L, Some(25L))
          result <- side.reader.earliestRoundWithCompleteAppActivity()
        } yield result.value shouldBe 21L
      }

      s"[$sideName] only consider the meta row from own history_id" in {
        for {
          (facade1, _) <- newStores()
          (facade2, _) <- newStores()
          side1 = pickSide(facade1)
          side2 = pickSide(facade2)
          _ <- side2.insertMeta(1, 0, 1000L, 10L, Some(11L))
          _ <- side1.insertMeta(1, 0, 3000L, 50L, None)
          result <- side1.reader.earliestRoundWithCompleteAppActivity()
        } yield result shouldBe None
      }
    }
  }

  "earliestIngestedRound on either side" should {
    sides.foreach { case (sideName, pickSide) =>
      s"[$sideName] return None when no meta record exists" in {
        for {
          (facade, _) <- newStores()
          side = pickSide(facade)
          result <- side.reader.earliestIngestedRound()
        } yield result shouldBe None
      }

      s"[$sideName] return the earliest ingested round even when last_archived_round is not set" in {
        for {
          (facade, _) <- newStores()
          side = pickSide(facade)
          _ <- side.insertMeta(1, 0, 1000L, 42L, None)
          result <- side.reader.earliestIngestedRound()
        } yield result.value shouldBe 42L
      }

      s"[$sideName] use the current version's meta row when multiple meta rows exist" in {
        for {
          (facade, _) <- newStores()
          side = pickSide(facade)
          _ <- side.insertMeta(0, 0, 1000L, 10L, Some(15L))
          _ <- side.insertMeta(1, 0, 2000L, 20L, None)
          result <- side.reader.earliestIngestedRound()
        } yield result.value shouldBe 20L
      }

      s"[$sideName] only consider the meta row from own history_id" in {
        for {
          (facade1, _) <- newStores()
          (facade2, _) <- newStores()
          side1 = pickSide(facade1)
          side2 = pickSide(facade2)
          _ <- side2.insertMeta(1, 0, 1000L, 10L, Some(11L))
          result <- side1.reader.earliestIngestedRound()
        } yield result shouldBe None
      }
    }
  }

  "latestRoundWithCompleteAppActivity on either side" should {
    sides.foreach { case (sideName, pickSide) =>
      s"[$sideName] return None when no meta row exists" in {
        for {
          (facade, _) <- newStores()
          side = pickSide(facade)
          result <- side.reader.latestRoundWithCompleteAppActivity()
        } yield result shouldBe None
      }

      s"[$sideName] return None when last_archived_round is not set" in {
        for {
          (facade, _) <- newStores()
          side = pickSide(facade)
          _ <- side.insertMeta(1, 0, 1000L, 42L, None)
          result <- side.reader.latestRoundWithCompleteAppActivity()
        } yield result shouldBe None
      }

      s"[$sideName] only consider the meta row from own history_id" in {
        for {
          (facade1, _) <- newStores()
          (facade2, _) <- newStores()
          side1 = pickSide(facade1)
          side2 = pickSide(facade2)
          _ <- side2.insertMeta(1, 0, 1000L, 10L, Some(11L))
          result <- side1.reader.latestRoundWithCompleteAppActivity()
        } yield result shouldBe None
      }
    }
  }

  private sealed trait SideFixture {
    def insertMeta(
        codeVersion: Int,
        userVersion: Int,
        startedIngestingAt: Long,
        earliestIngestedRound: Long,
        lastArchivedRound: Option[Long],
    ): Future[Unit]
    def reader: DbActivityRecordMetaStore#MetaReader
  }

  private val sides: Seq[(String, DbActivityRecordMetaStore => SideFixture)] = Seq(
    "app" -> { (facade: DbActivityRecordMetaStore) =>
      new SideFixture {
        def insertMeta(
            codeVersion: Int,
            userVersion: Int,
            startedIngestingAt: Long,
            earliestIngestedRound: Long,
            lastArchivedRound: Option[Long],
        ): Future[Unit] =
          facade.insertAppActivityRecordMetaForTesting(
            codeVersion,
            userVersion,
            startedIngestingAt,
            earliestIngestedRound,
            lastArchivedRound,
          )
        def reader = facade.appMeta
      }
    },
    "extra" -> { (facade: DbActivityRecordMetaStore) =>
      new SideFixture {
        def insertMeta(
            codeVersion: Int,
            userVersion: Int,
            startedIngestingAt: Long,
            earliestIngestedRound: Long,
            lastArchivedRound: Option[Long],
        ): Future[Unit] =
          facade.insertExtraActivityRecordMetaForTesting(
            codeVersion,
            userVersion,
            startedIngestingAt,
            earliestIngestedRound,
            lastArchivedRound,
          )
        def reader = facade.extraMeta
      }
    },
  )

  private val storeCounter = new java.util.concurrent.atomic.AtomicLong(1)

  private def newStores(
      versions: DbActivityRecordMetaStore.IngestionVersions =
        DbActivityRecordMetaStore.IngestionVersions(1, 0),
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
      val facade = new DbActivityRecordMetaStore(
        storage,
        updateHistory,
        versions,
        isFirstSv,
        loggerFactory,
      )
      val appStore = new DbAppActivityRecordStore(
        storage,
        updateHistory,
        facade,
        isFirstSv,
        initialRound,
        loggerFactory,
      )
      (facade, appStore)
    }
  }

  private def runEnsureExtraMeta(
      facade: DbActivityRecordMetaStore,
      ingestionStart: (Long, Long),
      lastArchivedRoundO: Option[Long] = None,
  ): Future[DbActivityRecordMetaStore.MetaCheckResult] =
    futureUnlessShutdownToFuture(
      storage.underlying.queryAndUpdate(
        facade.ensureExtraMetaDBIO(ingestionStart, lastArchivedRoundO, exitOnDowngrade = false),
        "test.ensureExtraMeta",
      )
    )

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}

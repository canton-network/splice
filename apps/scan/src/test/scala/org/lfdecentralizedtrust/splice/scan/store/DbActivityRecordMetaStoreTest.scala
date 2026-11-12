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
        (metaStore, _) <- newStores()
        startedAt <- metaStore.startedIngestingAt
        earliestComplete <- metaStore.earliestRoundWithCompleteAppActivity()
        latestComplete <- metaStore.latestRoundWithCompleteAppActivity()
      } yield {
        startedAt shouldBe None
        earliestComplete shouldBe None
        latestComplete shouldBe None
      }
    }

    "bound earliestRoundWithCompleteAppActivity by max of the two sides" in {
      for {
        (metaStore, _) <- newStores(initialRound = 5L)
        // Seed both meta rows and advance last_archived_round past
        // earliest_ingested_round + 1 for both. App-side earliest-complete: 5.
        // Extra-side earliest-complete: 7.
        _ <- metaStore.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(5L))
        _ <- metaStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 6L, Some(7L))
        combined <- metaStore.earliestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe Some(7L)
      }
    }

    "defer earliestRoundWithCompleteAppActivity to the app side when the extra meta row is absent" in {
      // PoC: extra has no meta row → extra is "not participating" and the
      // combine reports app-side's earliest-complete alone. This keeps
      // `RewardComputationTrigger` emitting tasks on networks that haven't
      // enabled the hook.
      for {
        (metaStore, _) <- newStores(initialRound = 5L)
        _ <- metaStore.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(5L))
        combined <- metaStore.earliestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe Some(5L)
      }
    }

    "return None for earliestRoundWithCompleteAppActivity when extra meta is present but incomplete" in {
      // Extra meta row exists but has no last_archived_round → strict combine
      // returns None. (Mirror of the extra-absent case above: once extra is
      // participating, both sides must be complete.)
      for {
        (metaStore, _) <- newStores(initialRound = 5L)
        _ <- metaStore.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(5L))
        _ <- metaStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, None)
        combined <- metaStore.earliestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe None
      }
    }

    "bound latestRoundWithCompleteAppActivity by min of the two sides" in {
      for {
        (metaStore, _) <- newStores(initialRound = 5L)
        // App-side latest-complete: 10. Extra-side latest-complete: 8.
        _ <- metaStore.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(10L))
        _ <- metaStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, Some(8L))
        combined <- metaStore.latestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe Some(8L)
      }
    }

    "return None for latestRoundWithCompleteAppActivity when extra meta is present but incomplete" in {
      // Extra-side meta present, but no last_archived_round yet. Extra is
      // participating, so the strict combine returns None.
      for {
        (metaStore, _) <- newStores(initialRound = 5L)
        _ <- metaStore.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(10L))
        _ <- metaStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, None)
        combined <- metaStore.latestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe None
      }
    }

    "defer latestRoundWithCompleteAppActivity to the app side when the extra meta row is absent" in {
      for {
        (metaStore, _) <- newStores(initialRound = 5L)
        _ <- metaStore.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, Some(10L))
        combined <- metaStore.latestRoundWithCompleteAppActivity()
      } yield {
        combined shouldBe Some(10L)
      }
    }

    "report CannotProvide for ingestionStatusForRound when either side does" in {
      for {
        (metaStore, _) <- newStores(initialRound = 5L)
        // Both meta rows present with earliest_ingested_round = 4, so
        // each side alone reports CannotProvide for round 4 and
        // Undetermined for round 20. The combined result must agree.
        _ <- metaStore.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
        _ <- metaStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, None)
        atEarliest <- metaStore.ingestionStatusForRound(4L)
        beyond <- metaStore.ingestionStatusForRound(20L)
      } yield {
        atEarliest shouldBe RoundIngestionStatus.CannotProvide
        beyond shouldBe RoundIngestionStatus.Undetermined
      }
    }

    "report CannotProvide for ingestionStatusForRound when the extra side is behind" in {
      for {
        (metaStore, _) <- newStores(initialRound = 5L)
        // App-side earliest-ingested 4 (so round 20 looks Undetermined
        // on the app side alone). Extra-side earliest-ingested 25 (round
        // 20 is BELOW it → CannotProvide on the extra side). The combined
        // response must adopt extra's CannotProvide.
        _ <- metaStore.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
        _ <- metaStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 25L, None)
        combined <- metaStore.ingestionStatusForRound(20L)
      } yield {
        combined shouldBe RoundIngestionStatus.CannotProvide
      }
    }

    "report Undetermined for ingestionStatusForRound when neither side places the round below its earliest ingested" in {
      for {
        (metaStore, _) <- newStores(initialRound = 5L)
        _ <- metaStore.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
        _ <- metaStore.insertExtraActivityRecordMetaForTesting(1, 0, 200L, 4L, None)
        combined <- metaStore.ingestionStatusForRound(20L)
      } yield {
        combined shouldBe RoundIngestionStatus.Undetermined
      }
    }

    "defer ingestionStatusForRound to the app side when the extra meta row is absent" in {
      // PoC: extra has no meta row → combine reports whatever the app side
      // alone reports. On a network with no hook ingestion, this prevents the
      // combine from poisoning the status to CannotProvide.
      for {
        (metaStore, _) <- newStores(initialRound = 5L)
        _ <- metaStore.insertAppActivityRecordMetaForTesting(1, 0, 100L, 4L, None)
        atEarliest <- metaStore.ingestionStatusForRound(4L)
        beyond <- metaStore.ingestionStatusForRound(20L)
      } yield {
        atEarliest shouldBe RoundIngestionStatus.CannotProvide
        beyond shouldBe RoundIngestionStatus.Undetermined
      }
    }

    "take the min of startedIngestingAt when both sides have a value" in {
      for {
        (metaStore, _) <- newStores(initialRound = 5L)
        _ <- metaStore.insertAppActivityRecordMetaForTesting(1, 0, 1000L, 4L, None)
        _ <- metaStore.insertExtraActivityRecordMetaForTesting(1, 0, 500L, 4L, None)
        combined <- metaStore.startedIngestingAt
      } yield {
        combined shouldBe Some(500L)
      }
    }

    "fall back to the single non-None startedIngestingAt when only one side has a meta row" in {
      for {
        (metaStore, _) <- newStores(initialRound = 5L)
        _ <- metaStore.insertAppActivityRecordMetaForTesting(1, 0, 1000L, 4L, None)
        combined <- metaStore.startedIngestingAt
      } yield {
        combined shouldBe Some(1000L)
      }
    }

    // ============ Extra-side meta ownership ============

    "insert a new extra-side meta row on first ensureExtraMetaDBIO" in {
      val startedAt = 123456L
      val earliestRound = 6L
      for {
        (metaStore, _) <- newStores(initialRound = 7L)
        result <- runEnsureExtraMeta(metaStore, (startedAt, earliestRound))
        loaded <- metaStore.lookupExtraActivityRecordMeta(1, 0)
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
        (metaStore, _) <- newStores(initialRound = 5L)
        _ <- metaStore.insertExtraActivityRecordMetaForTesting(1, 0, 999L, 4L, Some(10L))
        loaded <- metaStore.lookupExtraActivityRecordMeta(1, 0)
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
        (metaStore, _) <- newStores(initialRound = 7L)
        _ <- metaStore.insertExtraActivityRecordMetaForTesting(1, 0, 100L, 6L, None)
        reloadedBefore <- metaStore.lookupExtraActivityRecordMeta(1, 0)
        _ <- metaStore.bumpExtraLastArchivedRoundForTesting(round = 12L)
        reloadedAfter <- metaStore.lookupExtraActivityRecordMeta(1, 0)
      } yield {
        reloadedBefore.value.lastArchivedRound shouldBe None
        reloadedAfter.value.lastArchivedRound shouldBe Some(12L)
      }
    }
  }

  // ============ Per-side meta semantics ============
  // Each test runs twice — once against the app meta table, once against
  // the extra meta table — to pin that each shared SQL is correct regardless
  // of which side it reads.

  private sealed trait SideFixture {
    def insertMeta(
        codeVersion: Int,
        userVersion: Int,
        startedIngestingAt: Long,
        earliestIngestedRound: Long,
        lastArchivedRound: Option[Long],
    ): Future[Unit]
    def table: String
  }

  private val sides: Seq[(String, DbActivityRecordMetaStore => SideFixture)] = Seq(
    "app" -> { (metaStore: DbActivityRecordMetaStore) =>
      new SideFixture {
        def insertMeta(
            codeVersion: Int,
            userVersion: Int,
            startedIngestingAt: Long,
            earliestIngestedRound: Long,
            lastArchivedRound: Option[Long],
        ): Future[Unit] =
          metaStore.insertAppActivityRecordMetaForTesting(
            codeVersion,
            userVersion,
            startedIngestingAt,
            earliestIngestedRound,
            lastArchivedRound,
          )
        def table = metaStore.Tables.appActivityRecordMeta
      }
    },
    "extra" -> { (metaStore: DbActivityRecordMetaStore) =>
      new SideFixture {
        def insertMeta(
            codeVersion: Int,
            userVersion: Int,
            startedIngestingAt: Long,
            earliestIngestedRound: Long,
            lastArchivedRound: Option[Long],
        ): Future[Unit] =
          metaStore.insertExtraActivityRecordMetaForTesting(
            codeVersion,
            userVersion,
            startedIngestingAt,
            earliestIngestedRound,
            lastArchivedRound,
          )
        def table = metaStore.Tables.extraActivityRecordMeta
      }
    },
  )

  "earliestRoundWithCompleteAppActivity on either side" should {
    sides.foreach { case (sideName, pickSide) =>
      s"[$sideName] return None when no meta record exists" in {
        for {
          (metaStore, _) <- newStores()
          side = pickSide(metaStore)
          result <- metaStore.earliestCompleteRoundOf(side.table)
        } yield result shouldBe None
      }

      s"[$sideName] return None when last_archived_round is not set" in {
        for {
          (metaStore, _) <- newStores()
          side = pickSide(metaStore)
          _ <- side.insertMeta(1, 0, 1000L, 0L, None)
          result <- metaStore.earliestCompleteRoundOf(side.table)
        } yield result shouldBe None
      }

      s"[$sideName] return None while only the first ingested round has been archived" in {
        for {
          (metaStore, _) <- newStores()
          side = pickSide(metaStore)
          _ <- side.insertMeta(1, 0, 1000L, 42L, Some(42L))
          result <- metaStore.earliestCompleteRoundOf(side.table)
        } yield result shouldBe None
      }

      s"[$sideName] return the second ingested round once it has been archived" in {
        for {
          (metaStore, _) <- newStores()
          side = pickSide(metaStore)
          _ <- side.insertMeta(1, 0, 1000L, 42L, Some(43L))
          result <- metaStore.earliestCompleteRoundOf(side.table)
        } yield result.value shouldBe 43L
      }

      s"[$sideName] use current meta row when multiple meta rows exist" in {
        for {
          (metaStore, _) <- newStores()
          side = pickSide(metaStore)
          _ <- side.insertMeta(0, 0, 1000L, 10L, Some(15L))
          _ <- side.insertMeta(1, 0, 2000L, 20L, Some(25L))
          result <- metaStore.earliestCompleteRoundOf(side.table)
        } yield result.value shouldBe 21L
      }

      s"[$sideName] only consider the meta row from own history_id" in {
        for {
          (metaStore1, _) <- newStores()
          (metaStore2, _) <- newStores()
          side1 = pickSide(metaStore1)
          side2 = pickSide(metaStore2)
          _ <- side2.insertMeta(1, 0, 1000L, 10L, Some(11L))
          _ <- side1.insertMeta(1, 0, 3000L, 50L, None)
          result <- metaStore1.earliestCompleteRoundOf(side1.table)
        } yield result shouldBe None
      }
    }
  }

  "earliestIngestedRound on either side" should {
    sides.foreach { case (sideName, pickSide) =>
      s"[$sideName] return None when no meta record exists" in {
        for {
          (metaStore, _) <- newStores()
          side = pickSide(metaStore)
          result <- metaStore.earliestIngestedRoundOf(side.table)
        } yield result shouldBe None
      }

      s"[$sideName] return the earliest ingested round even when last_archived_round is not set" in {
        for {
          (metaStore, _) <- newStores()
          side = pickSide(metaStore)
          _ <- side.insertMeta(1, 0, 1000L, 42L, None)
          result <- metaStore.earliestIngestedRoundOf(side.table)
        } yield result.value shouldBe 42L
      }

      s"[$sideName] use the current version's meta row when multiple meta rows exist" in {
        for {
          (metaStore, _) <- newStores()
          side = pickSide(metaStore)
          _ <- side.insertMeta(0, 0, 1000L, 10L, Some(15L))
          _ <- side.insertMeta(1, 0, 2000L, 20L, None)
          result <- metaStore.earliestIngestedRoundOf(side.table)
        } yield result.value shouldBe 20L
      }

      s"[$sideName] only consider the meta row from own history_id" in {
        for {
          (metaStore1, _) <- newStores()
          (metaStore2, _) <- newStores()
          side1 = pickSide(metaStore1)
          side2 = pickSide(metaStore2)
          _ <- side2.insertMeta(1, 0, 1000L, 10L, Some(11L))
          result <- metaStore1.earliestIngestedRoundOf(side1.table)
        } yield result shouldBe None
      }
    }
  }

  "latestRoundWithCompleteAppActivity on either side" should {
    sides.foreach { case (sideName, pickSide) =>
      s"[$sideName] return None when no meta row exists" in {
        for {
          (metaStore, _) <- newStores()
          side = pickSide(metaStore)
          result <- metaStore.latestCompleteRoundOf(side.table)
        } yield result shouldBe None
      }

      s"[$sideName] return None when last_archived_round is not set" in {
        for {
          (metaStore, _) <- newStores()
          side = pickSide(metaStore)
          _ <- side.insertMeta(1, 0, 1000L, 42L, None)
          result <- metaStore.latestCompleteRoundOf(side.table)
        } yield result shouldBe None
      }

      s"[$sideName] only consider the meta row from own history_id" in {
        for {
          (metaStore1, _) <- newStores()
          (metaStore2, _) <- newStores()
          side1 = pickSide(metaStore1)
          side2 = pickSide(metaStore2)
          _ <- side2.insertMeta(1, 0, 1000L, 10L, Some(11L))
          result <- metaStore1.latestCompleteRoundOf(side1.table)
        } yield result shouldBe None
      }
    }
  }

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
      val metaStore = new DbActivityRecordMetaStore(
        storage,
        updateHistory,
        versions,
        isFirstSv,
        loggerFactory,
      )
      val appStore = new DbAppActivityRecordStore(
        storage,
        updateHistory,
        metaStore,
        isFirstSv,
        initialRound,
        loggerFactory,
      )
      (metaStore, appStore)
    }
  }

  private def runEnsureExtraMeta(
      metaStore: DbActivityRecordMetaStore,
      ingestionStart: (Long, Long),
      lastArchivedRoundO: Option[Long] = None,
  ): Future[DbActivityRecordMetaStore.MetaCheckResult] =
    futureUnlessShutdownToFuture(
      storage.underlying.queryAndUpdate(
        metaStore.ensureExtraMetaDBIO(ingestionStart, lastArchivedRoundO, exitOnDowngrade = false),
        "test.ensureExtraMeta",
      )
    )

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}

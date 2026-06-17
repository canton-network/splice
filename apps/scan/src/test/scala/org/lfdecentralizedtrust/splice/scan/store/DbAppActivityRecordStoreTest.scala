package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore.*
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, StoreTestBase, UpdateHistory}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.daml.metrics.api.noop.NoOpMetricsFactory

import scala.concurrent.Future

class DbAppActivityRecordStoreTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest {

  private val migrationId = 0L
  private val roundNumber = 42L

  "DbAppActivityRecordStore" should {

    "insert app activity records" in {
      for {
        (store, historyId) <- newStore()
        verdictRowId <- insertVerdictRow(historyId, CantonTimestamp.now(), "update-1")

        record = AppActivityRecordT(
          verdictRowId = verdictRowId,
          roundNumber = roundNumber,
          appProviderParties = Seq("app1::provider", "app2::provider"),
          appActivityWeights = Seq(100L, 50L),
        )

        _ <- store.insertAppActivityRecords(Seq(record))
        loaded <- store.getRecordByVerdictRowId(verdictRowId)
      } yield {
        loaded.value shouldBe record
      }
    }

    "batch insert multiple app activity records efficiently" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()

        // Insert 50 verdict rows to get valid row_ids
        verdictRowIds <- Future.traverse((0 until 50).toList) { i =>
          insertVerdictRow(historyId, baseTs.plusSeconds(i.toLong), s"update-batch-$i")
        }

        records = verdictRowIds.zipWithIndex.map { case (rowId, i) =>
          mkRecord(
            verdictRowId = rowId,
            roundNumber = roundNumber + i.toLong,
            appProviderParties = Seq(s"app$i::provider"),
            appActivityWeights = Seq(i.toLong * 10),
          )
        }

        _ <- store.insertAppActivityRecords(records)
        _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, roundNumber, None)
        // Spot-check first, last and a middle record via row decoders
        first <- store.getRecordByVerdictRowId(verdictRowIds(0))
        middle <- store.getRecordByVerdictRowId(verdictRowIds(25))
        last <- store.getRecordByVerdictRowId(verdictRowIds(49))
        // Batch fetch a subset of records
        batchIds = Seq(verdictRowIds(0), verdictRowIds(10), verdictRowIds(49))
        batchResult <- store.getRecordsByVerdictRowIds(batchIds)
        // Batch fetch with empty input
        emptyResult <- store.getRecordsByVerdictRowIds(Seq.empty)
        // Batch fetch with a non-existent id mixed in
        missingId = -999L
        partialResult <- store.getRecordsByVerdictRowIds(Seq(verdictRowIds(0), missingId))
      } yield {
        first.value shouldBe records(0)
        middle.value shouldBe records(25)
        last.value shouldBe records(49)
        // Batch assertions
        batchResult should have size 3
        batchResult(verdictRowIds(0)) shouldBe records(0)
        batchResult(verdictRowIds(10)) shouldBe records(10)
        batchResult(verdictRowIds(49)) shouldBe records(49)
        emptyResult shouldBe empty
        partialResult should have size 1
        partialResult(verdictRowIds(0)) shouldBe records(0)
        partialResult.get(missingId) shouldBe None
      }
    }

    "only return records from own history_id" in {
      for {
        (store1, historyId1) <- newStore()
        (store2, historyId2) <- newStore()
        baseTs = CantonTimestamp.now()
        Seq(rowId2) <- insertRecordsForRounds(store2, historyId2, baseTs, ("other", 10L))
        _ <- store2.insertActivityRecordMeta(1, 0, baseTs.toMicros, 10L, None)
        Seq(rowId1) <- insertRecordsForRounds(
          store1,
          historyId1,
          baseTs.plusSeconds(1L),
          ("own", 20L),
        )
        _ <- store1.insertActivityRecordMeta(1, 0, baseTs.plusSeconds(1L).toMicros, 20L, None)
        // store1 should only see its own record
        single <- store1.getRecordByVerdictRowId(rowId1)
        otherSingle <- store1.getRecordByVerdictRowId(rowId2)
        batch <- store1.getRecordsByVerdictRowIds(Seq(rowId1, rowId2))
      } yield {
        single shouldBe defined
        single.value.roundNumber shouldBe 20L
        otherSingle shouldBe None
        batch should have size 1
        batch(rowId1).roundNumber shouldBe 20L
      }
    }

    "handle empty activities" in {
      for {
        (store, historyId) <- newStore()
        verdictRowId <- insertVerdictRow(historyId, CantonTimestamp.now(), "update-empty")

        countBefore <- countRecords()
        record =
          mkRecord(
            verdictRowId,
            100L,
            appProviderParties = Seq.empty,
            appActivityWeights = Seq.empty,
          )

        _ <- store.insertAppActivityRecords(Seq(record))
        countAfter <- countRecords()
      } yield {
        countAfter shouldBe (countBefore + 1)
      }
    }
  }

  "insertVerdictsWithAppActivityRecords" should {

    "insert verdicts and resolve placeholder verdictRowIds in activity records" in {
      for {
        (appStore, verdictStore) <- newStores()
        baseTs = CantonTimestamp.now()

        verdict1 = mkVerdict(verdictStore, "update-combined-1", baseTs)
        verdict2 = mkVerdict(verdictStore, "update-combined-2", baseTs.plusSeconds(1L))

        appActivityRecords = Seq(
          baseTs -> mkRecord(0L, 10L, Seq("app1::provider"), Seq(100L)),
          baseTs.plusSeconds(1L) -> mkRecord(0L, 11L, Seq("app2::provider"), Seq(200L)),
        )

        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(verdict1 -> noViews, verdict2 -> noViews),
          appActivityRecords,
          lastArchivedRoundO = Some(9L),
        )

        // Verify verdicts were inserted
        v1 <- verdictStore.getVerdictByUpdateId("update-combined-1")
        v2 <- verdictStore.getVerdictByUpdateId("update-combined-2")

        // Verify activity records have resolved row_ids (not 0)
        r1 <- appStore.getRecordByVerdictRowId(v1.value.rowId)
        r2 <- appStore.getRecordByVerdictRowId(v2.value.rowId)
        // Verify meta row was created as a side effect
        meta <- appStore.lookupActivityRecordMeta(1, 0)
      } yield {
        v1 shouldBe defined
        v2 shouldBe defined

        r1.value.verdictRowId shouldBe v1.value.rowId
        r1.value.roundNumber shouldBe 10L
        r1.value.appProviderParties shouldBe Seq("app1::provider")

        r2.value.verdictRowId shouldBe v2.value.rowId
        r2.value.roundNumber shouldBe 11L
        r2.value.appProviderParties shouldBe Seq("app2::provider")

        meta shouldBe defined
        meta.value.startedIngestingAt shouldBe baseTs.toMicros
        meta.value.earliestIngestedRound shouldBe 10L
        meta.value.lastArchivedRound shouldBe Some(9L)
      }
    }

    "advances last_archived_round even in abscense of activity records" in {
      for {
        (appStore, verdictStore) <- newStores()
        baseTs = CantonTimestamp.now()

        // First batch with activity records creates the meta row
        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(mkVerdict(verdictStore, "update-mono-1", baseTs) -> noViews),
          Seq(baseTs -> mkRecord(0L, 10L, Seq("app1::provider"), Seq(100L))),
          lastArchivedRoundO = Some(9L),
        )
        // A later batch without activity records still advances the round
        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(mkVerdict(verdictStore, "update-mono-2", baseTs.plusSeconds(1L)) -> noViews),
          Seq.empty,
          lastArchivedRoundO = Some(10L),
        )
        meta <- appStore.lookupActivityRecordMeta(1, 0)
      } yield {
        meta.value.lastArchivedRound shouldBe Some(10L)
      }
    }

    "not write last_archived_round when no meta row exists" in {
      for {
        (appStore, verdictStore) <- newStores()
        baseTs = CantonTimestamp.now()

        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(mkVerdict(verdictStore, "update-no-meta", baseTs) -> noViews),
          Seq.empty,
          lastArchivedRoundO = Some(7L),
        )
        meta <- appStore.lookupActivityRecordMeta(1, 0)
      } yield {
        meta shouldBe None
      }
    }

    "insert verdicts without activity records when appActivityRecords is empty" in {
      for {
        (appStore, verdictStore) <- newStores()
        baseTs = CantonTimestamp.now()

        verdict = mkVerdict(verdictStore, "update-no-activity", baseTs)

        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(verdict -> noViews),
          Seq.empty,
        )

        v <- verdictStore.getVerdictByUpdateId("update-no-activity")
        countAfter <- countRecords()
        // No meta row should be created when there are no activity records
        meta <- appStore.lookupActivityRecordMeta(1, 0)
      } yield {
        v shouldBe defined
        countAfter shouldBe 0L
        meta shouldBe None
      }
    }

    "only create activity records for verdicts that have them" in {
      for {
        (appStore, verdictStore) <- newStores()
        baseTs = CantonTimestamp.now()

        // Three verdicts, but only the first and third have activity records
        verdict1 = mkVerdict(verdictStore, "update-with-1", baseTs)
        verdict2 = mkVerdict(verdictStore, "update-without", baseTs.plusSeconds(1L))
        verdict3 = mkVerdict(verdictStore, "update-with-2", baseTs.plusSeconds(2L))

        appActivityRecords = Seq(
          baseTs -> mkRecord(0L, 10L, Seq("app1::provider"), Seq(100L)),
          baseTs.plusSeconds(2L) -> mkRecord(0L, 12L, Seq("app3::provider"), Seq(300L)),
        )

        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(verdict1 -> noViews, verdict2 -> noViews, verdict3 -> noViews),
          appActivityRecords,
        )

        v1 <- verdictStore.getVerdictByUpdateId("update-with-1")
        v2 <- verdictStore.getVerdictByUpdateId("update-without")
        v3 <- verdictStore.getVerdictByUpdateId("update-with-2")

        r1 <- appStore.getRecordByVerdictRowId(v1.value.rowId)
        r2 <- appStore.getRecordByVerdictRowId(v2.value.rowId)
        r3 <- appStore.getRecordByVerdictRowId(v3.value.rowId)

        totalRecords <- countRecords()
      } yield {
        // All three verdicts should be inserted
        v1 shouldBe defined
        v2 shouldBe defined
        v3 shouldBe defined

        // Only first and third have activity records
        r1.value.roundNumber shouldBe 10L
        r1.value.appProviderParties shouldBe Seq("app1::provider")

        r2 shouldBe None

        r3.value.roundNumber shouldBe 12L
        r3.value.appProviderParties shouldBe Seq("app3::provider")

        totalRecords shouldBe 2L
      }
    }

    "skip activity records with no matching verdict timestamp" in {
      for {
        (appStore, verdictStore) <- newStores()
        baseTs = CantonTimestamp.now()

        verdict = mkVerdict(verdictStore, "update-mismatch", baseTs)

        // Activity record has a timestamp that doesn't match any verdict
        unmatchedTs = baseTs.plusSeconds(999L)
        appActivityRecords = Seq(
          unmatchedTs -> mkRecord(0L, 42L, Seq("orphan::provider"), Seq(300L))
        )

        _ <- verdictStore.insertVerdictsWithAppActivityRecords(
          Seq(verdict -> noViews),
          appActivityRecords,
        )

        v <- verdictStore.getVerdictByUpdateId("update-mismatch")
        r <- appStore.getRecordByVerdictRowId(v.value.rowId)
        countAfter <- countRecords()
      } yield {
        v shouldBe defined
        r shouldBe None
        countAfter shouldBe 0L
      }
    }
  }

  "earliestRoundWithCompleteAppActivity" should {

    "return None when no meta record exists" in {
      for {
        (store, _) <- newStore()
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "return None when last_archived_round is not set" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(1, 0, 0L, 0L, None)
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "return None while only the first ingested round has been archived" in {
      for {
        (store, _) <- newStore()
        baseTs = CantonTimestamp.now()
        _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, 42L, Some(42L))
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "return the second ingested round once it has been archived" in {
      for {
        (store, _) <- newStore()
        baseTs = CantonTimestamp.now()
        _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, 42L, Some(43L))
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        result.value shouldBe 43L
      }
    }

    "not depend on activity records existing in rounds after earliest" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        _ <- insertRecordsForRounds(store, historyId, baseTs, ("gap-10", 10L))
        // Only round 10 has activity; rounds 11 and 12 have zero records but
        // their archival makes them complete.
        _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, 10L, Some(12L))
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        result.value shouldBe 11L
      }
    }

    "use current meta row when multiple meta rows exist" in {
      for {
        (store, _) <- newStore()
        baseTs = CantonTimestamp.now()
        // Old meta row from a previous ingestion run starting at round 10
        _ <- store.insertActivityRecordMeta(0, 0, baseTs.toMicros, 10L, Some(15L))
        // Current meta row starting at round 20
        _ <- store.insertActivityRecordMeta(1, 0, baseTs.plusSeconds(10L).toMicros, 20L, Some(25L))
        result <- store.earliestRoundWithCompleteAppActivity()
      } yield {
        // Should use the current meta row (round 20), not the old one (round 10)
        result.value shouldBe 21L
      }
    }

    "only consider the meta row from own history_id" in {
      for {
        (store1, _) <- newStore()
        (store2, _) <- newStore()
        baseTs = CantonTimestamp.now()
        _ <- store2.insertActivityRecordMeta(1, 0, baseTs.toMicros, 10L, Some(11L))
        // store1's meta has no archived round yet
        _ <- store1.insertActivityRecordMeta(1, 0, baseTs.plusSeconds(2L).toMicros, 50L, None)
        result <- store1.earliestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "on first SV" should {

      "return round 0 on fresh network" in {
        for {
          (store, historyId) <- newStore(isFirstSv = true)
          baseTs = CantonTimestamp.now()
          // ensureMetaDBIO sets earliest_ingested_round = -1 on fresh isFirstSv network
          _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, -1L, Some(1L))
          _ <- insertRecordsForRounds(
            store,
            historyId,
            baseTs,
            ("round-0", 0L),
            ("round-1", 1L),
          )
          result <- store.earliestRoundWithCompleteAppActivity()
        } yield {
          // query returns -1 + 1 = 0
          result.value shouldBe 0L
        }
      }

      "return None until last_archived_round is set" in {
        for {
          (store, historyId) <- newStore(isFirstSv = true)
          baseTs = CantonTimestamp.now()
          _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, -1L, None)
          _ <- insertRecordsForRounds(store, historyId, baseTs, ("round-0", 0L))
          result <- store.earliestRoundWithCompleteAppActivity()
        } yield {
          result shouldBe None
        }
      }
    }

    "on first SV after version bump" should {

      "return version 2 earliest round after version bump" in {
        for {
          (store, historyId) <- newStore(
            versions = DbAppActivityRecordStore.IngestionVersions(2, 0),
            isFirstSv = true,
          )
          baseTs = CantonTimestamp.now()
          _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, 0L, Some(1L))
          _ <- store.insertActivityRecordMeta(
            2,
            0,
            baseTs.plusSeconds(10L).toMicros,
            10L,
            Some(11L),
          )
          _ <- insertRecordsForRounds(
            store,
            historyId,
            baseTs,
            ("round-10", 10L),
            ("round-11", 11L),
          )
          result <- store.earliestRoundWithCompleteAppActivity()
        } yield {
          result.value shouldBe 11L
        }
      }

      "return version 2 earliest round when version bump via ensureMeta" in {
        for {
          (store, historyId) <- newStore(
            versions = DbAppActivityRecordStore.IngestionVersions(2, 0),
            isFirstSv = true,
          )
          baseTs = CantonTimestamp.now()
          // Version 1 meta row exists from prior run
          _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, 0L, Some(1L))
          // Version 2 meta row added by ensureMeta
          _ <- runEnsureMeta(store, Some((baseTs.toMicros + 1000000L, 10L)), Some(11L))
          _ <- insertRecordsForRounds(
            store,
            historyId,
            baseTs,
            ("round-10", 10L),
            ("round-11", 11L),
          )
          result <- store.earliestRoundWithCompleteAppActivity()
        } yield {
          // Query reads version 2 meta (earliest_ingested_round = 10),
          // returns Some(11).
          result.value shouldBe 11L
        }
      }
    }
  }

  "lookupActivityRecordMeta" should {

    "return None when no meta row exists" in {
      for {
        (store, _) <- newStore()
        result <- store.lookupActivityRecordMeta(1, 0)
      } yield {
        result shouldBe None
      }
    }

    "return the meta row after insert" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(
          codeVersion = 1,
          userVersion = 0,
          startedIngestingAt = 1000000L,
          earliestIngestedRound = 0L,
          lastArchivedRound = None,
        )
        result <- store.lookupActivityRecordMeta(1, 0)
      } yield {
        result shouldBe defined
        result.value.codeVersion shouldBe 1
        result.value.userVersion shouldBe 0
        result.value.startedIngestingAt shouldBe 1000000L
        result.value.lastArchivedRound shouldBe None
      }
    }

    "return the matching version when multiple rows exist" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(
          codeVersion = 1,
          userVersion = 0,
          startedIngestingAt = 1000000L,
          earliestIngestedRound = 0L,
          lastArchivedRound = None,
        )
        _ <- store.insertActivityRecordMeta(
          codeVersion = 2,
          userVersion = 1,
          startedIngestingAt = 2000000L,
          earliestIngestedRound = 5L,
          lastArchivedRound = Some(7L),
        )
        result1 <- store.lookupActivityRecordMeta(1, 0)
        result2 <- store.lookupActivityRecordMeta(2, 1)
        resultMissing <- store.lookupActivityRecordMeta(3, 0)
      } yield {
        result1.value.startedIngestingAt shouldBe 1000000L
        result1.value.lastArchivedRound shouldBe None
        result2.value.startedIngestingAt shouldBe 2000000L
        result2.value.earliestIngestedRound shouldBe 5L
        result2.value.lastArchivedRound shouldBe Some(7L)
        resultMissing shouldBe None
      }
    }

    "isolate meta rows by history_id" in {
      for {
        (store1, _) <- newStore()
        (store2, _) <- newStore()
        _ <- store1.insertActivityRecordMeta(
          codeVersion = 1,
          userVersion = 0,
          startedIngestingAt = 1000000L,
          earliestIngestedRound = 0L,
          lastArchivedRound = None,
        )
        _ <- store2.insertActivityRecordMeta(
          codeVersion = 1,
          userVersion = 0,
          startedIngestingAt = 9000000L,
          earliestIngestedRound = 0L,
          lastArchivedRound = None,
        )
        result1 <- store1.lookupActivityRecordMeta(1, 0)
        result2 <- store2.lookupActivityRecordMeta(1, 0)
      } yield {
        result1.value.startedIngestingAt shouldBe 1000000L
        result2.value.startedIngestingAt shouldBe 9000000L
      }
    }

    "not affect other history_id on insert" in {
      for {
        (store1, _) <- newStore()
        (store2, _) <- newStore()
        _ <- store1.insertActivityRecordMeta(
          codeVersion = 1,
          userVersion = 0,
          startedIngestingAt = 1000000L,
          earliestIngestedRound = 0L,
          lastArchivedRound = None,
        )
        _ <- store2.insertActivityRecordMeta(
          codeVersion = 1,
          userVersion = 0,
          startedIngestingAt = 1000000L,
          earliestIngestedRound = 0L,
          lastArchivedRound = None,
        )
        _ <- store1.insertActivityRecordMeta(
          codeVersion = 99,
          userVersion = 99,
          startedIngestingAt = 9999999L,
          earliestIngestedRound = 0L,
          lastArchivedRound = None,
        )
        result2 <- store2.lookupActivityRecordMeta(1, 0)
      } yield {
        result2.value.codeVersion shouldBe 1
        result2.value.userVersion shouldBe 0
        result2.value.startedIngestingAt shouldBe 1000000L
      }
    }
  }

  "ensureMetaDBIO" should {

    "return NotReady when no meta row and no activity records" in {
      for {
        (store, _) <- newStore()
        result <- runEnsureMeta(store, None)
      } yield {
        result shouldBe NotReady
      }
    }

    "insert meta on first call and resume on second" in {
      for {
        (store, _) <- newStore()
        r1 <- runEnsureMeta(store, Some((1000000L, 10L)), Some(9L))
        r2 <- runEnsureMeta(store, None)
        meta <- store.lookupActivityRecordMeta(1, 0)
      } yield {
        r1 shouldBe Checked(InsertMeta)
        r2 shouldBe Checked(Resume)
        meta.value.startedIngestingAt shouldBe 1000000L
        meta.value.earliestIngestedRound shouldBe 10L
        meta.value.lastArchivedRound shouldBe Some(9L)
      }
    }

    "startedIngestingAt loads from DB after ensureMetaDBIO inserts" in {
      for {
        (store, _) <- newStore()
        // Before ensure, no meta row — startedIngestingAt returns None
        beforeO <- store.startedIngestingAt
        _ <- runEnsureMeta(store, Some((1000000L, 10L)))
        // After ensure, the read path should load from DB
        afterO <- store.startedIngestingAt
      } yield {
        beforeO shouldBe None
        afterO shouldBe Some(1000000L)
      }
    }

    "return Resume when versions match existing meta" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(1, 0, 1000000L, 10L, None)
        result <- runEnsureMeta(store, Some((2000000L, 20L)))
        meta <- store.lookupActivityRecordMeta(1, 0)
      } yield {
        result shouldBe Checked(Resume)
        meta.value.startedIngestingAt shouldBe 1000000L
        meta.value.earliestIngestedRound shouldBe 10L
      }
    }

    "insert new row and return InsertMeta on version bump" in {
      for {
        (store, _) <- newStore(DbAppActivityRecordStore.IngestionVersions(2, 0))
        _ <- store.insertActivityRecordMeta(1, 0, 1000000L, 10L, Some(15L))
        result <- runEnsureMeta(store, Some((2000000L, 20L)), Some(19L))
        meta <- store.lookupActivityRecordMeta(2, 0)
        oldMeta <- store.lookupActivityRecordMeta(1, 0)
      } yield {
        result shouldBe Checked(InsertMeta)
        meta.value.codeVersion shouldBe 2
        meta.value.startedIngestingAt shouldBe 2000000L
        meta.value.earliestIngestedRound shouldBe 20L
        meta.value.lastArchivedRound shouldBe Some(19L)
        oldMeta.value.lastArchivedRound shouldBe Some(15L)
      }
    }

    "return DowngradeDetected without modifying the row" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(2, 0, 1000000L, 10L, None)
        result <- runEnsureMeta(store, Some((2000000L, 20L)))
        meta <- store.lookupActivityRecordMeta(2, 0)
      } yield {
        result shouldBe Checked(DowngradeDetected(1, 0, 2, 0))
        meta.value.codeVersion shouldBe 2
        meta.value.startedIngestingAt shouldBe 1000000L
        meta.value.earliestIngestedRound shouldBe 10L
      }
    }

    "on first SV" should {

      "set earliest_ingested_round to -1 on fresh network" in {
        for {
          (store, _) <- newStore(isFirstSv = true)
          r1 <- runEnsureMeta(store, Some((1000000L, 10L)))
          meta <- store.lookupActivityRecordMeta(1, 0)
        } yield {
          r1 shouldBe Checked(InsertMeta)
          meta.value.earliestIngestedRound shouldBe -1L
        }
      }

      "use actual earliest_ingested_round on version bump" in {
        for {
          (store, _) <- newStore(
            versions = DbAppActivityRecordStore.IngestionVersions(2, 0),
            isFirstSv = true,
          )
          _ <- store.insertActivityRecordMeta(1, 0, 1000000L, 5L, None)
          r1 <- runEnsureMeta(store, Some((2000000L, 10L)))
          meta <- store.lookupActivityRecordMeta(2, 0)
        } yield {
          r1 shouldBe Checked(InsertMeta)
          meta.value.earliestIngestedRound shouldBe 10L
        }
      }
    }
  }

  "latestRoundWithCompleteAppActivity" should {

    "return None when no meta row exists" in {
      for {
        (store, _) <- newStore()
        result <- store.latestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "return None when records exist but no meta row" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        _ <- insertRecordsForRounds(
          store,
          historyId,
          baseTs,
          ("no-meta-10", 10L),
          ("no-meta-11", 11L),
        )
        // No meta row — latestRound returns None, consistent with earliestRound
        result <- store.latestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "return None when last_archived_round is not set" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(1, 0, CantonTimestamp.now().toMicros, 42L, None)
        result <- store.latestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }

    "return the round from meta regardless of activity records" in {
      for {
        (store, historyId) <- newStore()
        baseTs = CantonTimestamp.now()
        _ <- insertRecordsForRounds(store, historyId, baseTs, ("gap-10", 10L))
        _ <- store.insertActivityRecordMeta(1, 0, baseTs.toMicros, 10L, Some(12L))
        result <- store.latestRoundWithCompleteAppActivity()
      } yield {
        result.value shouldBe 12L
      }
    }

    "only consider the meta row from own history_id" in {
      for {
        (store1, _) <- newStore()
        (store2, _) <- newStore()
        baseTs = CantonTimestamp.now()
        _ <- store2.insertActivityRecordMeta(1, 0, baseTs.toMicros, 10L, Some(11L))
        result <- store1.latestRoundWithCompleteAppActivity()
      } yield {
        result shouldBe None
      }
    }
  }

  "assertCompleteActivity" should {

    "fail when no meta row exists" in {
      for {
        (store, _) <- newStore()
        result <- store.assertCompleteActivity(10L).failed
      } yield {
        result.getMessage should include("Incomplete app activity")
      }
    }

    "fail when last_archived_round is not set" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(1, 0, CantonTimestamp.now().toMicros, 10L, None)
        result <- store.assertCompleteActivity(11L).failed
      } yield {
        result.getMessage should include("Incomplete app activity")
      }
    }

    "pass only for rounds after the first ingested round and up to the last archived round" in {
      for {
        (store, _) <- newStore()
        _ <- store.insertActivityRecordMeta(1, 0, CantonTimestamp.now().toMicros, 10L, Some(12L))
        _ <- store.assertCompleteActivity(11L)
        _ <- store.assertCompleteActivity(12L)
        tooEarly <- store.assertCompleteActivity(10L).failed
        tooLate <- store.assertCompleteActivity(13L).failed
      } yield {
        tooEarly.getMessage should include("Incomplete app activity")
        tooLate.getMessage should include("Incomplete app activity")
      }
    }
  }

  private def mkRecord(
      verdictRowId: Long,
      roundNumber: Long,
      appProviderParties: Seq[String],
      appActivityWeights: Seq[Long],
  ): AppActivityRecordT =
    AppActivityRecordT(
      verdictRowId = verdictRowId,
      roundNumber = roundNumber,
      appProviderParties = appProviderParties,
      appActivityWeights = appActivityWeights,
    )

  /** Insert verdict rows with incrementing timestamps and matching activity records.
    * Returns the generated verdict row IDs.
    */
  private def insertRecordsForRounds(
      store: DbAppActivityRecordStore,
      historyId: Long,
      baseTs: CantonTimestamp,
      rounds: (String, Long)*
  ): Future[Seq[Long]] =
    for {
      pairs <- Future.traverse(rounds.zipWithIndex.toList) { case ((suffix, round), i) =>
        insertVerdictRow(historyId, baseTs.plusSeconds(i.toLong), s"update-$suffix")
          .map(_ -> round)
      }
      _ <- store.insertAppActivityRecords(
        pairs.zipWithIndex.map { case ((rowId, round), i) =>
          mkRecord(rowId, round, Seq("app1::provider"), Seq((i + 1).toLong * 100L))
        }
      )
    } yield pairs.map(_._1)

  private def runEnsureMeta(
      store: DbAppActivityRecordStore,
      ingestionStart: Option[(Long, Long)],
      lastArchivedRoundO: Option[Long] = None,
  ): Future[EnsureResult] =
    futureUnlessShutdownToFuture(
      storage.underlying.queryAndUpdate(
        store.ensureMetaDBIO(ingestionStart, lastArchivedRoundO),
        "test.ensureMeta",
      )(implicitly, implicitly, _ => false)
    )

  private val testDomain = SynchronizerId.tryFromString("test::domain")

  private val storeCounter = new java.util.concurrent.atomic.AtomicLong(1)

  /** Creates a new store and returns it along with a unique history_id
    * obtained from UpdateHistory initialization.
    */
  private def newStore(
      versions: DbAppActivityRecordStore.IngestionVersions =
        DbAppActivityRecordStore.IngestionVersions(1, 0),
      isFirstSv: Boolean = false,
  ): Future[(DbAppActivityRecordStore, Long)] = {
    val n = storeCounter.getAndIncrement()
    val participantId = mkParticipantId(s"activity-test-$n")
    val updateHistory = new UpdateHistory(
      storage.underlying,
      migrationId,
      s"app_activity_test_$n",
      participantId,
      dsoParty,
      BackfillingRequirement.BackfillingNotRequired,
      loggerFactory,
      enableissue12777Workaround = true,
      enableImportUpdateBackfill = false,
      HistoryMetrics(NoOpMetricsFactory, migrationId),
    )
    updateHistory.ingestionSink.initialize().map { _ =>
      val store = new DbAppActivityRecordStore(
        storage.underlying,
        updateHistory,
        versions,
        isFirstSv,
        loggerFactory,
      )
      (store, updateHistory.historyId)
    }
  }

  /** Creates both an app activity record store and a verdict store backed by
    * the same UpdateHistory, for testing insertVerdictsWithAppActivityRecords.
    */
  private def newStores(): Future[(DbAppActivityRecordStore, DbScanVerdictStore)] = {
    val participantId = mkParticipantId("activity-test")
    val updateHistory = new UpdateHistory(
      storage.underlying,
      migrationId,
      "app_activity_combined_test",
      participantId,
      dsoParty,
      BackfillingRequirement.BackfillingNotRequired,
      loggerFactory,
      enableissue12777Workaround = true,
      enableImportUpdateBackfill = false,
      HistoryMetrics(NoOpMetricsFactory, migrationId),
    )
    updateHistory.ingestionSink.initialize().map { _ =>
      val appStore = new DbAppActivityRecordStore(
        storage.underlying,
        updateHistory,
        DbAppActivityRecordStore.IngestionVersions(1, 0),
        isFirstSv = false,
        loggerFactory,
      )
      val verdictStore = new DbScanVerdictStore(
        storage.underlying,
        updateHistory,
        Some(appStore),
        loggerFactory,
      )
      (appStore, verdictStore)
    }
  }

  private def mkVerdict(
      verdictStore: DbScanVerdictStore,
      updateId: String,
      recordTs: CantonTimestamp,
  ): verdictStore.VerdictT =
    new verdictStore.VerdictT(
      rowId = 0L,
      migrationId = migrationId,
      domainId = testDomain,
      recordTime = recordTs,
      finalizationTime = recordTs,
      submittingParticipantUid = "participant1",
      verdictResult = DbScanVerdictStore.VerdictResultDbValue.Accepted,
      mediatorGroup = 0,
      updateId = updateId,
      submittingParties = Seq.empty,
      transactionRootViews = Seq.empty,
      trafficSummaryO = None,
    )

  private val noViews: Long => Seq[DbScanVerdictStore.TransactionViewT] = _ => Seq.empty

  /** Insert a minimal verdict row into scan_verdict_store and return its generated row_id. */
  private def insertVerdictRow(
      historyId: Long,
      recordTime: CantonTimestamp,
      updateId: String,
  ): Future[Long] = {
    import storage.api.jdbcProfile.api.*
    futureUnlessShutdownToFuture(
      storage.underlying
        .queryAndUpdate(
          sql"""
          insert into scan_verdict_store(
            history_id, migration_id, domain_id, record_time, finalization_time,
            submitting_participant_uid, verdict_result, mediator_group,
            update_id, submitting_parties, transaction_root_views
          ) values (
            $historyId, $migrationId, 'test-domain', $recordTime, $recordTime,
            'participant1', 1, 0,
            $updateId, array[]::text[], array[]::integer[]
          ) returning row_id
        """.as[Long].head,
          "test.insertVerdictRow",
        )(implicitly, implicitly, _ => false)
    )
  }

  /** Test helper to count records in the database */
  private def countRecords(): Future[Long] = {
    import storage.api.jdbcProfile.api.*
    futureUnlessShutdownToFuture(
      storage.underlying
        .query(
          sql"""
          select count(*)
          from app_activity_record_store
        """.as[Long].head,
          "test.countRecords",
        )
    )
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}

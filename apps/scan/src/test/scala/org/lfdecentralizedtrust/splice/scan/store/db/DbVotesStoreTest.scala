// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import cats.data.NonEmptyList
import com.daml.ledger.javaapi.data.{
  DamlRecord,
  Event,
  ExercisedEvent,
  Transaction,
  Unit as damlUnit,
}
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext, SynchronizerAlias}
import io.grpc.{Status, StatusRuntimeException}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_MiningRound_Archive
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_DsoRules,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_UpdateSvRewardWeight
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.voterequestoutcome.{
  VRO_Accepted,
  VRO_Expired,
  VRO_Rejected,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules,
  DsoRules_CloseVoteRequestResult,
  DsoRules_UpdateSvRewardWeight,
  Reason,
  Vote,
  VoteRequest,
  VoteRequestOutcome as VRO,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.ClosedMiningRound
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  TransactionTreeUpdate,
  TreeUpdateOrOffsetCheckpoint,
}
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.scan.store.{ScanKeyValueStore, ScanStore}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionSink.IngestionStart.{
  InitializeAcsAtLatestOffset,
  InitializeAcsAtOffset,
  ResumeAtOffset,
  UpdateHistoryInitAtLatestPrunedOffset,
}
import org.lfdecentralizedtrust.splice.store.StoreTestBase.IngestSingleElementSink
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId
import org.lfdecentralizedtrust.splice.store.db.{DbKeyValueStore, SplicePostgresTest}
import org.lfdecentralizedtrust.splice.store.events.DsoRulesCloseVoteRequest
import org.lfdecentralizedtrust.splice.store.{
  HardLimit,
  HistoricalVotesStore,
  Limit,
  PageLimit,
  StoreTestBase,
  VoteResultsFilters,
}
import org.lfdecentralizedtrust.splice.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import org.slf4j.event.Level
import slick.dbio.DBIO
import slick.jdbc.GetResult
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Collections, Optional}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Checks that the historical vote queries of [[DbVotesStore]] return the same data
  * as the txlog based implementation in [[DbScanStore]].
  *
  * TODO(#7727): Remove the comparisons with scanStore after we remove the txlog based implementation.
  * TODO(#7728): Move the "listVoteRequestResults" and "countVoteRequestResults" tests from ScanStoreTest to here.
  */
class DbVotesStoreTest
    extends StoreTestBase
    with HasExecutionContext
    with HasActorSystem
    with SplicePostgresTest {

  private val participantName = "DbVotesStoreTest"

  "DbVotesStore" should {
    "behave the same as the txlog-based votes store in ScanStore" should {
      "ingest the same vote results as the txlog" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, voteFixtures)
          txLogCount <- stores.scanStore.countVoteRequestResults(VoteResultsFilters())
          votesCount <- stores.votesStore.countVoteRequestResults(VoteResultsFilters())
          txLogResults <- listAllPages(stores.scanStore, VoteResultsFilters(), pageSize = 1000)
          votesResults <- listAllPages(stores.votesStore, VoteResultsFilters(), pageSize = 1000)
        } yield {
          // Make sure the comparisons below are not trivially satisfied by two empty stores
          txLogCount shouldBe voteFixtures.flatMap(_.votes).size.toLong
          votesCount shouldBe txLogCount
          votesResults.flatten should contain theSameElementsAs voteFixtures.flatMap(_.votes)
          votesResults shouldBe txLogResults
        }
      }

      "return the same counts as the txlog for all filters" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, voteFixtures)
          counts <- MonadUtil.sequentialTraverse(filters) { filter =>
            for {
              txLogCount <- stores.scanStore.countVoteRequestResults(filter)
              votesCount <- stores.votesStore.countVoteRequestResults(filter)
            } yield (filter, txLogCount, votesCount)
          }
        } yield forAll(counts) { case (filter, txLogCount, votesCount) =>
          withClue(s"Filter $filter: ") {
            votesCount shouldBe txLogCount
          }
        }
      }

      "return the same pages as the txlog for all filters and page sizes" in {
        val cases = for {
          filter <- filters
          pageSize <- Seq(1, 2, 3, 1000)
        } yield (filter, pageSize)
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, voteFixtures)
          pages <- MonadUtil.sequentialTraverse(cases) { case (filter, pageSize) =>
            for {
              txLogPages <- listAllPages(stores.scanStore, filter, pageSize)
              votesPages <- listAllPages(stores.votesStore, filter, pageSize)
            } yield (filter, pageSize, txLogPages, votesPages)
          }
        } yield forAll(pages) { case (filter, pageSize, txLogPages, votesPages) =>
          withClue(s"Filter $filter, page size $pageSize: ") {
            votesPages shouldBe txLogPages
          }
        }
      }

      "return the same data as the txlog after a restart" in {
        val (beforeRestart, afterRestart) = voteFixtures.splitAt(voteFixtures.size / 2)
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, beforeRestart)
          restarted <- mkStores()
          _ <- ingestAll(restarted, afterRestart)
          txLogCount <- restarted.scanStore.countVoteRequestResults(VoteResultsFilters())
          votesCount <- restarted.votesStore.countVoteRequestResults(VoteResultsFilters())
          txLogPages <- listAllPages(restarted.scanStore, VoteResultsFilters(), pageSize = 2)
          votesPages <- listAllPages(restarted.votesStore, VoteResultsFilters(), pageSize = 2)
        } yield {
          txLogCount shouldBe voteFixtures.flatMap(_.votes).size.toLong
          votesCount shouldBe txLogCount
          votesPages shouldBe txLogPages
        }
      }
    }

    "pagination" should {
      "follow the sort order, not the insertion order" in {
        // Sort key (effective date, or completed at), then record time and event number, all descending
        def sortKey(result: DsoRules_CloseVoteRequestResult): Instant = result.outcome match {
          case accepted: VRO_Accepted => accepted.effectiveAt
          case _ => result.completedAt
        }
        val expected = voteFixtures
          .flatMap(fixture =>
            fixture.votes.zipWithIndex.map { case (result, index) =>
              (sortKey(result), fixture.recordTime, index, result)
            }
          )
          .sortBy { case (key, recordTime, index, _) => (key, recordTime, index) }
          .reverse
          .map(_._4)
        for {
          stores <- mkStores()
          // Ingested in the opposite order of the record times
          _ <- ingestAll(stores, voteFixtures.reverse)
          pages <- listAllPages(stores.votesStore, VoteResultsFilters(), pageSize = 2)
        } yield pages.flatten shouldBe expected
      }
    }

    "contract_id" should {
      "be the DsoRules on which the choice was exercised" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, voteFixtures)
          contractIds <- listContractIds()
        } yield {
          contractIds should have size voteFixtures.flatMap(_.votes).size.toLong
          all(contractIds.map(_._2)) shouldBe Some(dsoRulesCid)
        }
      }
    }

    "listVoteResults" should {
      "return the same vote results as listVoteRequestResults, with the data that identifies them" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, voteFixtures)
          page <- stores.votesStore.listVoteResults(DbVotesStore.Filters(), PageLimit.Max, None)
          resultsPage <- stores.votesStore.listVoteRequestResults(
            VoteResultsFilters(),
            PageLimit.Max,
            None,
          )
          rows <- listAllRows(stores.votesStore)
        } yield {
          page.resultsInPage should have size voteFixtures.flatMap(_.votes).size.toLong
          page.resultsInPage.map(r =>
            DsoRules_CloseVoteRequestResult.fromJson(r.choiceResult)
          ) shouldBe resultsPage.resultsInPage
          page.nextPageToken.map(token => (token.recordTime, token.eventNumber)) shouldBe
            page.resultsInPage.lastOption.map(r => (r.recordTime, r.eventNumber))
          page.resultsInPage.map(r =>
            (
              r.recordTime,
              r.synchronizerId,
              r.updateId,
              r.eventNumber,
              r.contractId,
              r.choiceResult,
            )
          ) should contain theSameElementsAs rows.map(r =>
            (
              CantonTimestamp.ofEpochMicro(r.recordTime),
              r.synchronizerId,
              r.updateId,
              r.eventNumber,
              r.contractId,
              r.choiceResult,
            )
          )
        }
      }

      "page in the same order as listVoteRequestResults" in {
        def go(
            votesStore: DbVotesStore,
            after: Option[DbVotesStore.VoteResultsPageToken],
            acc: Seq[DbVotesStore.ServedVoteResult],
        ): Future[Seq[DbVotesStore.ServedVoteResult]] =
          votesStore
            .listVoteResults(DbVotesStore.Filters(), PageLimit.tryCreate(2), after)
            .flatMap { page =>
              if (page.resultsInPage.isEmpty) Future.successful(acc)
              else go(votesStore, page.nextPageToken, acc ++ page.resultsInPage)
            }
        for {
          stores <- mkStores()
          // Ingested in the opposite order of the record times
          _ <- ingestAll(stores, voteFixtures.reverse)
          v0Pages <- listAllPages(stores.votesStore, VoteResultsFilters(), pageSize = 2)
          paged <- go(stores.votesStore, None, Seq.empty)
        } yield {
          v0Pages.flatten should have size voteFixtures.flatMap(_.votes).size.toLong
          paged.map(r => DsoRules_CloseVoteRequestResult.fromJson(r.choiceResult)) shouldBe
            v0Pages.flatten
        }
      }

      "round trip the page token, and reject invalid ones" in {
        val token = DbVotesStore.VoteResultsPageToken(
          CantonTimestamp.assertFromLong(3L),
          CantonTimestamp.assertFromLong(2L),
          1,
        )
        DbVotesStore.VoteResultsPageToken.tryDecodeFromBase64(token.encodeToBase64) shouldBe token
        forAll(
          Seq("not base64!", "e30=", java.util.Base64.getEncoder.encodeToString("42".getBytes))
        ) { invalid =>
          inside(scala.util.Try(DbVotesStore.VoteResultsPageToken.tryDecodeFromBase64(invalid))) {
            case scala.util.Failure(e: StatusRuntimeException) =>
              e.getStatus.getCode shouldBe Status.Code.INVALID_ARGUMENT
          }
        }
      }

      // /v1/admin/sv/voteresults serves the stored JSON, /v0/admin/sv/voteresults re-encodes the decoded result
      "store the JSON in the same format as /v0/admin/sv/voteresults encodes it" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, voteFixtures)
          page <- stores.votesStore.listVoteResults(DbVotesStore.Filters(), PageLimit.Max, None)
        } yield {
          page.resultsInPage should have size voteFixtures.flatMap(_.votes).size.toLong
          forAll(page.resultsInPage) { row =>
            val v0Encoding = ApiCodecCompressed
              .apiValueToJsValue(
                Contract.javaValueToLfValue(
                  DsoRules_CloseVoteRequestResult.fromJson(row.choiceResult).toValue
                )
              )
              .compactPrint
            io.circe.parser.parse(row.choiceResult) shouldBe io.circe.parser.parse(v0Encoding)
          }
        }
      }
    }

    "the migration of contract_id" should {
      "fill the contract id from the update history, and leave rows without a match null" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, voteFixtures)
          updateIds <- listContractIds().map(_.map(_._1).distinct.sorted)
          (withHistory, withoutHistory) = updateIds.splitAt(updateIds.size / 2)
          _ <- clearContractIds()
          historyId <- insertHistory(participantName, dsoParty.toProtoPrimitive)
          otherHistoryId <- insertHistory("OtherParticipant", dsoParty.toProtoPrimitive)
          _ <- MonadUtil.sequentialTraverse(withHistory.zipWithIndex) { case (updateId, i) =>
            for {
              _ <- insertExercise(historyId, updateId, 0, "DsoRules_CloseVoteRequest", s"cid-$i")
              // Another choice in the same update, and the same update in the history of another participant
              _ <- insertExercise(historyId, updateId, 1, "DsoRules_SomeOtherChoice", s"other-$i")
              _ <- insertExercise(
                otherHistoryId,
                updateId,
                0,
                "DsoRules_CloseVoteRequest",
                s"other-participant-$i",
              )
            } yield ()
          }
          _ <- runMigration()
          contractIds <- listContractIds()
        } yield {
          withHistory should not be empty
          withoutHistory should not be empty
          forAll(contractIds) { case (updateId, contractId) =>
            val i = withHistory.indexOf(updateId)
            contractId shouldBe (if (i >= 0) Some(s"cid-$i") else None)
          }
        }
      }
    }

    "undecodable vote results" should {
      "be ingested as placeholders that are listed like other votes" in {
        val good = voteFixtures.head
        val goodVotes = good.votes
        // The result of this vote can't be decoded
        val bad = dsoRulesExercise(
          DsoRulesCloseVoteRequest.choice.name,
          mkCloseVoteRequest(new VoteRequest.ContractId(nextCid())),
          com.daml.ledger.javaapi.data.Unit.getInstance(),
        )
        for {
          stores <- mkStores()
          // Created after the stores are initialized, so that its offset is after the ACS offset
          tx = mkTx(
            nextOffset(),
            goodVotes.map(closeVoteRequestEvent) :+ bad,
            dummyDomain,
            effectiveAt = good.recordTime,
            recordTime = good.recordTime,
          )
          // Ingests through the raw sink: the `testIngestionSink` used by `ingestMulti` fails on any error log.
          // Decoding the result logs an internal error before the store logs that it ingests a placeholder.
          // The txlog parser fails on the same result. Like `testIngestionSink`, tolerate the warning
          // that the ingestion of the transaction was repeated, which is how the tests check idempotency.
          _ <- loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
            stores.scanStore.multiDomainAcsStore.ingestionSink
              .ingestUpdate(dummyDomain, TransactionTreeUpdate(tx)),
            entries => {
              val (repeatedIngestion, others) =
                entries.partition(_.message.contains(RepeatedIngestionWarningMessage))
              repeatedIngestion.size should be <= 1
              forExactly(1, others)(
                _.errorMessage should include("Ingesting a placeholder instead")
              )
            },
          )
          count <- stores.votesStore.countVoteRequestResults(VoteResultsFilters())
          results <- listAllPages(stores.votesStore, VoteResultsFilters(), pageSize = 10)
        } yield {
          // The other votes of the transaction are ingested, and the undecodable one is a placeholder
          count shouldBe goodVotes.size + 1L
          results.flatten should contain allElementsOf goodVotes
          results.flatten.map(_.request.requester) should contain(DbVotesStore.FailedToParseVote)
        }
      }
    }

    "filter by effective date" should {
      "compare timestamps, not strings" in {
        // Every accepted result is effective at least 2.5 seconds after `base`.
        // As strings, "...:02.500Z" would sort before "...:02Z".
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, voteFixtures)
          accepted <- stores.votesStore.countVoteRequestResults(
            VoteResultsFilters(accepted = Some(true))
          )
          from <- stores.votesStore.countVoteRequestResults(
            VoteResultsFilters(effectiveFrom = Some(base.plusSeconds(2).toString))
          )
          to <- stores.votesStore.countVoteRequestResults(
            VoteResultsFilters(effectiveTo = Some(base.plusSeconds(3).toString))
          )
        } yield {
          from shouldBe accepted
          to shouldBe 1L
        }
      }

      "include the bounds, for one-sided and two-sided filters alike" in {
        // Two accepted results are effective exactly at `bound`, one is effective before it (at 2.5 seconds)
        val bound = base.plusSeconds(10).toString
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, voteFixtures)
          accepted <- stores.votesStore.countVoteRequestResults(
            VoteResultsFilters(accepted = Some(true))
          )
          from <- stores.votesStore.countVoteRequestResults(
            VoteResultsFilters(effectiveFrom = Some(bound))
          )
          to <- stores.votesStore.countVoteRequestResults(
            VoteResultsFilters(effectiveTo = Some(bound))
          )
          both <- stores.votesStore.countVoteRequestResults(
            VoteResultsFilters(effectiveFrom = Some(bound), effectiveTo = Some(bound))
          )
        } yield {
          from shouldBe accepted - 1
          to shouldBe 3L
          both shouldBe 2L
        }
      }

      "reject dates that cannot be parsed" in {
        val invalid = Seq(
          VoteResultsFilters(effectiveFrom = Some("Invalid Date")),
          VoteResultsFilters(effectiveTo = Some("")),
          VoteResultsFilters(
            effectiveFrom = Some("garbage"),
            effectiveTo = Some(base.plusSeconds(3).toString),
          ),
        )
        def code(f: Future[?]): Future[Status.Code] = f.failed.map {
          case e: StatusRuntimeException => e.getStatus.getCode
          case e => fail(s"Unexpected exception $e")
        }
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, voteFixtures)
          codes <- MonadUtil.sequentialTraverse(invalid) { filters =>
            for {
              listCode <- code(
                stores.votesStore.listVoteRequestResults(filters, PageLimit.tryCreate(10), None)
              )
              countCode <- code(stores.votesStore.countVoteRequestResults(filters))
            } yield Seq(listCode, countCode)
          }
        } yield codes.flatten should contain only Status.Code.INVALID_ARGUMENT
      }
    }

    "first ingested record time" should {
      "be empty initially" in {
        for {
          stores <- mkStores()
          firstRecordTime <- stores.votesStore.getFirstIngestedRecordTime()
        } yield {
          firstRecordTime shouldBe empty
        }
      }

      "be empty after ingesting a checkpoint" in {
        for {
          stores <- mkStores()
          _ <- stores.scanStore.multiDomainAcsStore.ingestionSink.ingestUpdateBatch(
            NonEmptyList.of(
              TreeUpdateOrOffsetCheckpoint.Checkpoint(
                new com.daml.ledger.javaapi.data.OffsetCheckpoint(
                  nextOffset(),
                  Collections.emptyList(),
                )
              )
            )
          )
          firstRecordTime <- stores.votesStore.getFirstIngestedRecordTime()
        } yield {
          firstRecordTime shouldBe empty
        }
      }

      "be non-empty after ingesting transactions following a checkpoint" in {
        val recordTime = CantonTimestamp.now()
        for {
          stores <- mkStores()
          _ <- stores.scanStore.multiDomainAcsStore.ingestionSink.ingestUpdateBatch(
            NonEmptyList.of(
              TreeUpdateOrOffsetCheckpoint.Checkpoint(
                new com.daml.ledger.javaapi.data.OffsetCheckpoint(
                  nextOffset(),
                  Collections.emptyList(),
                )
              )
            )
          )
          _ <- dummyDomain.create(
            appRewardCoupon(0, dsoParty, contractId = validContractId(1)),
            recordTime = recordTime.toInstant,
          )(stores.scanStore.multiDomainAcsStore)
          firstRecordTime <- stores.votesStore.getFirstIngestedRecordTime()
        } yield {
          firstRecordTime shouldBe Some(recordTime)
        }
      }

      "be non-empty after ingesting transactions" in {
        val recordTime = CantonTimestamp.now()
        for {
          stores <- mkStores()
          _ <- dummyDomain.create(
            appRewardCoupon(0, dsoParty, contractId = validContractId(1)),
            recordTime = recordTime.toInstant,
          )(stores.scanStore.multiDomainAcsStore)
          _ <- dummyDomain.create(
            appRewardCoupon(1, dsoParty, contractId = validContractId(2)),
            recordTime = recordTime.toInstant.plusSeconds(1L),
          )(stores.scanStore.multiDomainAcsStore)
          firstRecordTime <- stores.votesStore.getFirstIngestedRecordTime()
        } yield {
          firstRecordTime shouldBe Some(recordTime)
        }
      }

      "use the id of the TxLog store as store id" in {
        for {
          stores <- mkStores()
        } yield {
          stores.votesStore.storeId shouldBe TxLogStoreId.unwrap(stores.scanStore.txLogStoreId)
        }
      }

      "survive a reset of the ACS store" in {
        val (beforeReset, afterReset) = voteFixtures.splitAt(voteFixtures.size / 2)
        for {
          kvStore <- ScanKeyValueStore(
            dsoParty,
            mkParticipantId("DbVotesStoreTest"),
            storage,
            loggerFactory,
          )
          stores <- mkStoresWith(kvStore)
          _ <- ingestAll(stores, beforeReset)
          firstRecordTime <- stores.votesStore.getFirstIngestedRecordTime()
          // A new ACS store descriptor means that the ACS store starts out empty, while the TxLog store
          // (and with it the votes store) keeps its ingestion state.
          reset <- mkStoresWith(kvStore, acsStoreDescriptorUserVersion = Some(1L))
          _ <- ingestAll(reset, afterReset)
          resetFirstRecordTime <- reset.votesStore.getFirstIngestedRecordTime()
          count <- reset.votesStore.countVoteRequestResults(VoteResultsFilters())
        } yield {
          reset.votesStore.storeId shouldBe stores.votesStore.storeId
          resetFirstRecordTime shouldBe firstRecordTime
          count shouldBe voteFixtures.flatMap(_.votes).size.toLong
        }
      }

      "not be shared between stores with different store ids" in {
        val recordTime = CantonTimestamp.now()
        for {
          kvStore <- ScanKeyValueStore(
            dsoParty,
            mkParticipantId("DbVotesStoreTest"),
            storage,
            loggerFactory,
          )
          stores <- mkStoresWith(kvStore)
          otherStores <- mkStoresWith(kvStore, "DbVotesStoreTestOther")
          _ <- dummyDomain.create(
            appRewardCoupon(0, dsoParty, contractId = validContractId(1)),
            recordTime = recordTime.toInstant,
          )(stores.scanStore.multiDomainAcsStore)
          firstRecordTime <- stores.votesStore.getFirstIngestedRecordTime()
          otherFirstRecordTime <- otherStores.votesStore.getFirstIngestedRecordTime()
        } yield {
          otherStores.votesStore.storeId should not be stores.votesStore.storeId
          firstRecordTime shouldBe Some(recordTime)
          otherFirstRecordTime shouldBe empty
        }
      }
    }

    "backfilling cursor and completion" should {
      "be empty initially" in {
        for {
          stores <- mkStores()
          initial <- stores.votesStore.getBackfillingCursor()
        } yield initial shouldBe empty
      }

      "default to incomplete, until marked complete" in {
        for {
          stores <- mkStores()
          before <- stores.votesStore.isBackfillingComplete()
          _ <- stores.votesStore.markBackfillingComplete()
          after <- stores.votesStore.isBackfillingComplete()
        } yield {
          before shouldBe false
          after shouldBe true
        }
      }

      "not be shared between stores with different update histories" in {
        val cursor = DbVotesStore.VotesBackfillCursor(CantonTimestamp.now(), 0)
        for {
          kvStore <- ScanKeyValueStore(
            dsoParty,
            mkParticipantId("DbVotesStoreTest"),
            storage,
            loggerFactory,
          )
          stores <- mkStoresWith(kvStore)
          otherVotesStore <- mkStoresWith(kvStore, "update_history_votes_store_test_other")
            .map(_.votesStore)
          _ <- stores.votesStore.insertPeerVoteResults(Seq.empty, cursor)
          _ <- stores.votesStore.markBackfillingComplete()
          otherCursor <- otherVotesStore.getBackfillingCursor()
          otherComplete <- otherVotesStore.isBackfillingComplete()
        } yield {
          otherVotesStore.storeId should not be stores.votesStore.storeId
          otherCursor shouldBe empty
          otherComplete shouldBe false
        }
      }
    }

    "the migration from the txlog" should {
      "copy the vote results exactly as the ingestion inserted them" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, txLogVoteFixtures)
          expected <- listAllRows(stores.votesStore)
          expectedFirstIngested <- stores.votesStore.getFirstIngestedRecordTime()
          _ <- clearVotesStore(stores)
          _ <- markHistoriesComplete(stores)
          _ <- runTxLogMigration()
          actual <- listAllRows(stores.votesStore)
          firstIngested <- stores.votesStore.getFirstIngestedRecordTime()
          cursor <- stores.votesStore.getBackfillingCursor()
          complete <- stores.votesStore.isBackfillingComplete()
        } yield {
          // Make sure the comparison is not trivially satisfied by two empty stores
          expected should have size txLogVoteFixtures.flatMap(_.votes).size.toLong
          // The contract ids, which are not in the txlog, are found in the update history
          all(expected.map(_.contractId)) shouldBe Some(dsoRulesCid)
          actual shouldBe expected
          expectedFirstIngested should not be empty
          // Where live ingestion started is not up to the migration
          firstIngested shouldBe expectedFirstIngested
          // The txlog has every vote, so there is nothing to backfill
          cursor shouldBe empty
          complete shouldBe true
        }
      }

      "fill the contract ids that V081 could not find for votes that were ingested before" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, txLogVoteFixtures)
          expected <- listAllRows(stores.votesStore)
          // As if the update history had not ingested these votes yet when V081 ran
          _ <- clearContractIds()
          _ <- markHistoriesComplete(stores)
          _ <- runTxLogMigration()
          actual <- listAllRows(stores.votesStore)
          complete <- stores.votesStore.isBackfillingComplete()
        } yield {
          expected should not be empty
          all(expected.map(_.contractId)) shouldBe Some(dsoRulesCid)
          actual shouldBe expected
          complete shouldBe true
        }
      }

      "not copy anything if the update history does not have the contract id of a vote" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, txLogVoteFixtures)
          _ <- clearVotesStore(stores)
          _ <- markHistoriesComplete(stores)
          // The update history misses the exercise of one vote
          removed <- futureUnlessShutdownToFuture(
            storage.underlying.queryAndUpdate(
              sqlu"""delete from update_history_exercises_legacy
                     where row_id = (select min(row_id) from update_history_exercises_legacy
                                     where choice = 'DsoRules_CloseVoteRequest')""",
              "test.removeExercise",
            )
          )
          _ <- runTxLogMigration()
          rows <- listAllRows(stores.votesStore)
          cursor <- stores.votesStore.getBackfillingCursor()
          complete <- stores.votesStore.isBackfillingComplete()
        } yield {
          removed shouldBe 1
          txLogVoteFixtures.size should be > 1
          // All or nothing: the votes that have a contract id are not copied either
          rows shouldBe empty
          cursor shouldBe empty
          complete shouldBe false
        }
      }

      // Each of these breaks one of the conditions under which the migration copies the votes
      Seq[(String, (Stores, CantonTimestamp) => Future[Unit])](
        "the txlog backfilling is in progress" -> ((stores, _) =>
          setTxLogBackfillingComplete(stores, complete = false)
        ),
        "the txlog backfilling was never initialized" -> ((stores, _) =>
          futureUnlessShutdownToFuture(
            storage.update(
              sqlu"delete from txlog_backfilling_status where store_id = ${stores.votesStore.storeId}",
              "test.clearTxLogBackfillingStatus",
            )
          ).map(_ => ())
        ),
        "the update history backfilling is in progress" -> ((_, _) =>
          setUpdateHistoryState(participantName, complete = false)
        ),
        "the update history has not ingested up to where the votes store started" -> (
          (_, firstIngested) =>
            for {
              _ <- setUpdateHistoryState(
                participantName,
                complete = true,
                ingestedUpTo = firstIngested.minusSeconds(1),
              )
              // Also remove the updates of the votes from that point on
              _ <- futureUnlessShutdownToFuture(
                storage.underlying.queryAndUpdate(
                  DBIO
                    .seq(
                      sqlu"""delete from update_history_exercises_legacy
                             where record_time >= ${firstIngested.toMicros}""",
                      sqlu"""delete from update_history_transactions_legacy
                             where record_time >= ${firstIngested.toMicros}""",
                    )
                    .map(_ => ()),
                  "test.truncateUpdateHistory",
                )
              )
            } yield ()
        ),
        "the votes store has not started ingesting" -> ((_, _) =>
          futureUnlessShutdownToFuture(
            storage.update(
              sqlu"delete from key_value_store where key like 'votes_store_first_ingested_%'",
              "test.clearFirstIngested",
            )
          ).map(_ => ())
        ),
      ).foreach { case (condition, prepare) =>
        s"not copy anything if $condition" in {
          for {
            stores <- mkStores()
            _ <- ingestAll(stores, txLogVoteFixtures)
            firstIngested <- stores.votesStore
              .getFirstIngestedRecordTime()
              .map(_.getOrElse(fail("not ingested")))
            _ <- clearVotesStore(stores)
            _ <- markHistoriesComplete(stores)
            _ <- prepare(stores, firstIngested)
            _ <- runTxLogMigration()
            complete <- stores.votesStore.isBackfillingComplete()
            cursor <- stores.votesStore.getBackfillingCursor()
            rows <- listAllRows(stores.votesStore)
          } yield {
            complete shouldBe false
            cursor shouldBe empty
            rows shouldBe empty
          }
        }
      }

      "leave the first ingested record time of the live ingestion alone" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, txLogVoteFixtures)
          txLogFirstIngested <- stores.votesStore
            .getFirstIngestedRecordTime()
            .map(_.getOrElse(fail("not ingested")))
          // Live ingestion started after the txlog did
          _ <- setFirstIngestedRecordTime(stores, txLogFirstIngested.plusSeconds(100))
          _ <- markHistoriesComplete(stores)
          _ <- runTxLogMigration()
          firstIngested <- stores.votesStore.getFirstIngestedRecordTime()
        } yield firstIngested shouldBe Some(txLogFirstIngested.plusSeconds(100))
      }

      "migrate every txlog store on its own, and pair it with the key value store of its participant" in {
        val keyPattern = "votes_store_backfill_complete_%"
        for {
          kv <- ScanKeyValueStore(
            dsoParty,
            mkParticipantId("DbVotesStoreTest"),
            storage,
            loggerFactory,
          )
          otherKv <- ScanKeyValueStore(
            dsoParty,
            mkParticipantId("DbVotesStoreTestOther"),
            storage,
            loggerFactory,
          )
          stores <- mkStoresWith(kv)
          // Resetting the ACS store gives a new ACS store id, and keeps the txlog store
          _ <- mkStoresWith(kv, acsStoreDescriptorUserVersion = Some(1L))
          // Resetting the txlog store gives a new txlog store id, for the same party and participant
          resetTxLog <- mkStoresWith(kv, txLogStoreDescriptorUserVersion = Some(1L))
          // Another participant has its own key value store
          other <- mkStoresWith(otherKv, "DbVotesStoreTestOther")
          _ <- ingestAll(stores, txLogVoteFixtures.take(2))
          _ <- ingestAll(resetTxLog, txLogVoteFixtures.drop(2))
          _ <- ingestAll(other, txLogVoteFixtures.take(1))
          all = Seq(stores, resetTxLog, other)
          expectedRows <- MonadUtil.sequentialTraverse(all)(s => listAllRows(s.votesStore))
          _ <- MonadUtil.sequentialTraverse(all)(clearVotesStore)
          _ <- markHistoriesComplete(stores)
          _ <- markHistoriesComplete(resetTxLog)
          _ <- markHistoriesComplete(other, "DbVotesStoreTestOther")
          _ <- runTxLogMigration()
          actualRows <- MonadUtil.sequentialTraverse(all)(s => listAllRows(s.votesStore))
          completes <- MonadUtil.sequentialTraverse(all)(s => s.votesStore.isBackfillingComplete())
          completeKeys <- futureUnlessShutdownToFuture(
            storage.query(
              sql"select store_id, key from key_value_store where key like $keyPattern".as[
                (Int, String)
              ],
              "test.completeKeys",
            )
          )
        } yield {
          all.map(_.votesStore.storeId).distinct should have size 3
          expectedRows.map(_.size) shouldBe Seq(2, txLogVoteFixtures.size - 2, 1)
          // Each store gets exactly its own votes, and the txlog of the reset ACS store is not affected
          actualRows shouldBe expectedRows
          completes shouldBe Seq(true, true, true)
          // The keys are in the key value store of the participant of the txlog store, once per txlog store
          completeKeys.toSet shouldBe Set(
            kv.storeId -> DbVotesStore.backfillCompleteKey(stores.votesStore.storeId),
            kv.storeId -> DbVotesStore.backfillCompleteKey(resetTxLog.votesStore.storeId),
            otherKv.storeId -> DbVotesStore.backfillCompleteKey(other.votesStore.storeId),
          )
          completeKeys should have size 3
        }
      }

      "succeed without copying anything if a vote in the txlog cannot be copied" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, txLogVoteFixtures)
          _ <- markHistoriesComplete(stores)
          _ <- clearVotesStore(stores)
          // One row without an update id, which violates a not null constraint of votes_store_results
          _ <- futureUnlessShutdownToFuture(
            storage.underlying.queryAndUpdate(
              sqlu"""update scan_txlog_store set event_id = 'malformed'
                     where entry_number = (select min(entry_number) from scan_txlog_store
                                           where entry_type = 'vot' and store_id = ${stores.votesStore.storeId})""",
              "test.corruptTxLog",
            )
          )
          _ <- runTxLogMigration()
          rows <- listAllRows(stores.votesStore)
          cursor <- stores.votesStore.getBackfillingCursor()
          complete <- stores.votesStore.isBackfillingComplete()
        } yield {
          txLogVoteFixtures.size should be > 1
          // All or nothing: the other votes are not copied either, and backfilling starts from scratch
          rows shouldBe empty
          cursor shouldBe empty
          complete shouldBe false
        }
      }

      "do nothing on a database without scan stores" in {
        for {
          _ <- runTxLogMigration()
          rows <- futureUnlessShutdownToFuture(
            storage.query(
              sql"select count(*) from votes_store_results".as[Long].head,
              "test.countVoteRows",
            )
          )
        } yield rows shouldBe 0L
      }

    }

    "backfilling from peers" should {
      "page backwards by (record_time, event_number) and insert the same results" in {
        val cursor = DbVotesStore.VotesBackfillCursor(CantonTimestamp.MinValue, 0)
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, voteFixtures)
          fresh <- mkFreshVotesStore()
          peerRows <- listAllPeerPages(stores.votesStore, pageSize = 2)
          _ <- fresh.insertPeerVoteResults(peerRows, cursor)
          stored <- fresh.getBackfillingCursor()
          count <- fresh.countVoteRequestResults(VoteResultsFilters())
          results <- listAllPages(fresh, VoteResultsFilters(), pageSize = 1000)
          sourceRows <- listAllRows(stores.votesStore)
          copiedRows <- listAllRows(fresh)
        } yield {
          stored shouldBe Some(cursor)
          count shouldBe voteFixtures.flatMap(_.votes).size.toLong
          results.flatten should contain theSameElementsAs voteFixtures.flatMap(_.votes)
          // The columns that are derived from the result are the same as when ingesting it
          sourceRows should have size voteFixtures.flatMap(_.votes).size.toLong
          copiedRows shouldBe sourceRows
        }
      }

      "serve the identifying data and the stored result, nothing else" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, txLogVoteFixtures)
          served <- stores.votesStore.listBackfillPage(
            DbVotesStore.VotesBackfillCursor(CantonTimestamp.MaxValue, 0),
            PageLimit.Max,
          )
          rows <- listAllRows(stores.votesStore)
        } yield {
          served should have size txLogVoteFixtures.size.toLong
          // Newest first
          served.map(_.recordTime) shouldBe served.map(_.recordTime).sorted.reverse
          served.map(s => (s.recordTime, s.synchronizerId, s.updateId, s.eventNumber)) shouldBe
            rows.reverse.map(r =>
              (
                CantonTimestamp.ofEpochMicro(r.recordTime),
                r.synchronizerId,
                r.updateId,
                r.eventNumber,
              )
            )
          served.map(_.contractId) shouldBe rows.reverse.map(_.contractId)
          all(served.map(_.contractId)) shouldBe Some(dsoRulesCid)
          served.map(_.choiceResult) shouldBe rows.reverse.map(_.choiceResult)
        }
      }

      "extract the same rows from a transaction of the update history as the ingestion does" in {
        val storeId = 42
        forAll(voteFixtures) { fixture =>
          val tx = mkFixtureTx(fixture)(1L)
          val backfilled = DbVotesStore.backfilledVoteResults(tx).map(_.toInsert(storeId))
          backfilled should have size fixture.votes.size.toLong
          backfilled shouldBe DbVotesStore.parseTransaction(tx, storeId)
        }
      }

      "refuse to decode a result that cannot be decoded, instead of storing a placeholder" in {
        val thrown = the[DbVotesStore.UndecodablePeerVoteResult] thrownBy DbVotesStore
          .EncodedPeerVoteResult(
            CantonTimestamp.Epoch.plusSeconds(7),
            "synchronizer",
            "the-update",
            0,
            "the-contract",
            """{"not": "a vote result"}""",
          )
          .decode
        thrown.getMessage should include("the-update")
        thrown.getCause should not be null
      }

      "return an empty page once genesis is reached" in {
        for {
          stores <- mkStores()
          _ <- ingestAll(stores, voteFixtures)
          page <- stores.votesStore.listBackfillPage(
            DbVotesStore.VotesBackfillCursor(CantonTimestamp.MinValue, 0),
            PageLimit.Max,
          )
        } yield page shouldBe empty
      }
    }
  }

  private val base = Instant.parse("2024-03-01T10:00:00Z")

  private lazy val sv = userParty(42)
  private lazy val requesters = Seq(userParty(1), userParty(2), userParty(3))

  private def updateWeightAction(weight: Long) = new ARC_DsoRules(
    new SRARC_UpdateSvRewardWeight(
      new DsoRules_UpdateSvRewardWeight(sv.toProtoPrimitive, weight)
    )
  )

  private lazy val archiveRoundAction = new ARC_AmuletRules(
    new CRARC_MiningRound_Archive(
      new AmuletRules_MiningRound_Archive(new ClosedMiningRound.ContractId(nextCid()))
    )
  )

  private def voteResult(
      requester: PartyId,
      action: ActionRequiringConfirmation,
      completedAt: Instant,
      outcome: VRO,
  ): DsoRules_CloseVoteRequestResult = {
    val votes = requesters.map(p =>
      new Vote(p.toProtoPrimitive, true, new Reason("https://example.com", "ok"), Optional.empty())
    )
    new DsoRules_CloseVoteRequestResult(
      voteRequest(requester = requester, votes = votes, action = action).payload,
      completedAt,
      java.util.List.of(userParty(7).toProtoPrimitive),
      java.util.List.of(userParty(8).toProtoPrimitive, userParty(9).toProtoPrimitive),
      outcome,
    )
  }

  private def accepted(effectiveAt: Instant) = new VRO_Accepted(effectiveAt)
  private lazy val rejected = new VRO_Rejected(damlUnit.getInstance())
  private lazy val expired = new VRO_Expired(damlUnit.getInstance())

  /** A transaction closing one or more vote requests.
    *
    * @param nested If true, the vote closing exercises are children of another exercise node.
    */
  private case class VoteFixture(
      recordTime: Instant,
      votes: Seq[DsoRules_CloseVoteRequestResult],
      nested: Boolean = false,
  )

  // Transactions are listed in ingestion order, which deliberately differs from the sort order of the results.
  // The fixtures include ties in the sort key (both across and within transactions), timestamps of mixed precision,
  // results with and without an effective date, and multiple action types and requesters.
  private lazy val voteFixtures: Seq[VoteFixture] = {
    def recordTime(n: Int) = base.plus(1, ChronoUnit.DAYS).plusSeconds(n.toLong)
    Seq(
      VoteFixture(
        recordTime(1),
        Seq(
          voteResult(requesters(0), addUser666Action, recordTime(1), accepted(base.plusSeconds(10)))
        ),
      ),
      VoteFixture(
        recordTime(2),
        Seq(voteResult(requesters(1), addUser666Action, base.plusSeconds(1), rejected)),
      ),
      VoteFixture(
        recordTime(3),
        Seq(
          voteResult(
            requesters(2),
            updateWeightAction(30000L),
            recordTime(3),
            accepted(base.plusSeconds(10)),
          )
        ),
      ),
      VoteFixture(
        recordTime(4),
        Seq(
          voteResult(
            requesters(0),
            archiveRoundAction,
            base.plusSeconds(20).plusNanos(123000),
            expired,
          )
        ),
      ),
      VoteFixture(
        recordTime(5),
        Seq(
          voteResult(
            requesters(1),
            addUser666Action,
            recordTime(5),
            accepted(base.plusSeconds(2).plusMillis(500)),
          ),
          voteResult(requesters(2), updateWeightAction(40000L), base.plusSeconds(10), rejected),
          voteResult(
            requesters(0),
            archiveRoundAction,
            recordTime(5),
            accepted(base.plusSeconds(45).plusMillis(900)),
          ),
        ),
      ),
      VoteFixture(
        recordTime(6),
        Seq(
          voteResult(
            requesters(1),
            updateWeightAction(50000L),
            recordTime(6),
            accepted(base.plusSeconds(30)),
          ),
          voteResult(requesters(2), addUser666Action, base.plusSeconds(30), expired),
        ),
        nested = true,
      ),
      VoteFixture(
        recordTime(7),
        Seq(
          voteResult(
            requesters(0),
            addUser666Action,
            recordTime(7),
            accepted(base.plus(30, ChronoUnit.DAYS)),
          )
        ),
      ),
      VoteFixture(
        recordTime(8),
        Seq(
          voteResult(
            requesters(1),
            updateWeightAction(60000L),
            base.plusSeconds(10),
            rejected,
          )
        ),
      ),
    )
  }

  /** The subset of [[voteFixtures]] that can occur in the txlog: there, a vote is always the
    * root of its transaction, so a transaction closes exactly one vote request.
    */
  private def txLogVoteFixtures: Seq[VoteFixture] =
    voteFixtures.filter(f => f.votes.size == 1 && !f.nested)

  /** A row of `votes_store_results`, without the local `store_id` and `row_id`. */
  private case class VoteRow(
      recordTime: Long,
      synchronizerId: String,
      updateId: String,
      eventNumber: Int,
      contractId: Option[String],
      voteActionName: String,
      voteRequesterName: String,
      voteAccepted: Boolean,
      voteTrackingCid: String,
      voteCompletedAt: Option[Long],
      voteEffectiveAt: Option[Long],
      choiceResult: String,
      error: Option[String],
  )

  implicit private val getResultVoteRow: GetResult[VoteRow] = GetResult { r =>
    VoteRow(
      r.<<[Long],
      r.<<[String],
      r.<<[String],
      r.<<[Int],
      r.<<?[String],
      r.<<[String],
      r.<<[String],
      r.<<[Boolean],
      r.<<[String],
      r.<<?[Long],
      r.<<?[Long],
      r.<<[String],
      r.<<?[String],
    )
  }

  /** All columns of all rows of the votes store, oldest first. */
  private def listAllRows(votesStore: DbVotesStore): Future[Seq[VoteRow]] =
    futureUnlessShutdownToFuture(
      storage.query(
        sql"""select record_time, synchronizer_id, update_id, event_number, contract_id, vote_action_name,
                     vote_requester_name, vote_accepted, vote_tracking_cid, vote_completed_at,
                     vote_effective_at, choice_result, error
              from votes_store_results
              where store_id = ${votesStore.storeId}
              order by record_time, event_number""".as[VoteRow],
        "test.listAllRows",
      )
    )

  /** Brings the votes store into the state that it had before the migration: live ingestion has started
    * (the first ingested record time is kept), but there are no rows and no backfilling state.
    */
  private def clearVotesStore(stores: Stores): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.update(
        DBIO.seq(
          sqlu"delete from votes_store_results where store_id = ${stores.votesStore.storeId}",
          sqlu"delete from key_value_store where key like 'votes_store_backfill_%'",
        ),
        "test.clearVotesStore",
      )
    )

  private def setTxLogBackfillingComplete(stores: Stores, complete: Boolean): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.update(
        sqlu"""insert into txlog_backfilling_status (store_id, backfilling_complete)
               values (${stores.votesStore.storeId}, $complete)
               on conflict (store_id) do update set backfilling_complete = $complete""",
        "test.setTxLogBackfillingComplete",
      )
    ).map(_ => ())

  private val farFuture: CantonTimestamp = CantonTimestamp.ofEpochSecond(4102444800L) // 2100-01-01

  /** Sets the state of the update history of the given participant, as the migration reads it:
    * whether its backfilling is complete, and up to which record time it has ingested.
    */
  private def setUpdateHistoryState(
      participantName: String,
      complete: Boolean,
      ingestedUpTo: CantonTimestamp = farFuture,
  ): Future[Unit] =
    for {
      historyId <- insertHistory(participantName, dsoParty.toProtoPrimitive)
      _ <- futureUnlessShutdownToFuture(
        storage.underlying.queryAndUpdate(
          DBIO
            .seq(
              sqlu"""delete from update_history_backfilling where history_id = $historyId""",
              sqlu"""insert into update_history_last_ingested_offsets (history_id, migration_id)
                     values ($historyId, $domainMigrationId)
                     on conflict do nothing""",
              sqlu"""delete from update_history_transactions_legacy
                     where history_id = $historyId and update_id = 'last-ingested'""",
              sqlu"""insert into update_history_transactions_legacy
                       (history_id, update_id, record_time, participant_offset, domain_id, migration_id,
                        effective_at, root_event_ids)
                     values ($historyId, 'last-ingested', ${ingestedUpTo.toMicros}, 'last-ingested',
                             $dummyDomain, $domainMigrationId, ${ingestedUpTo.toMicros}, array[]::text[])""",
              sqlu"""insert into update_history_backfilling
                       (history_id, joining_migration_id, joining_domain_id, joining_update_id, complete,
                        import_updates_complete)
                     values ($historyId, $domainMigrationId, $dummyDomain, 'last-ingested', $complete, $complete)""",
            )
            .map(_ => ()),
          "test.setUpdateHistoryState",
        )
      )
    } yield ()

  /** The state of an SV on a long-running network, for which the migration copies the votes:
    * the txlog and the update history are complete, and the update history has the exercise of every vote.
    */
  private def markHistoriesComplete(
      stores: Stores,
      participantName: String = participantName,
  ): Future[Unit] =
    for {
      _ <- setTxLogBackfillingComplete(stores, complete = true)
      _ <- setUpdateHistoryState(participantName, complete = true)
      historyId <- insertHistory(participantName, dsoParty.toProtoPrimitive)
      _ <- insertTxLogVoteExercises(stores, historyId)
    } yield ()

  /** Inserts the transaction and the DsoRules_CloseVoteRequest exercise (on [[dsoRulesCid]]) of every vote
    * in the txlog of the given store into the update history, like the ingestion of the update history would.
    */
  private def insertTxLogVoteExercises(stores: Stores, historyId: Int): Future[Unit] = {
    val storeId = stores.votesStore.storeId
    futureUnlessShutdownToFuture(
      storage.underlying.queryAndUpdate(
        DBIO
          .seq(
            sqlu"""insert into update_history_transactions_legacy
                     (history_id, update_id, record_time, participant_offset, domain_id, migration_id,
                      effective_at, root_event_ids)
                   select $historyId, substring(t.event_id from '^#?(.*):[0-9]+$$'), t.record_time,
                          t.transaction_offset, t.domain_id, t.migration_id, t.record_time, array['#0']
                   from scan_txlog_store t
                   where t.store_id = $storeId and t.entry_type = 'vot'
                   on conflict do nothing""",
            sqlu"""insert into update_history_exercises_legacy
                     (history_id, event_id, update_row_id, child_event_ids, choice, package_name,
                      template_id_package_id, template_id_module_name, template_id_entity_name, contract_id,
                      consuming, argument, result, acting_parties, record_time, update_id, domain_id, migration_id)
                   select $historyId, '#' || u.update_id || ':0', u.row_id, array[]::text[],
                          'DsoRules_CloseVoteRequest', 'splice-dso-governance', 'pkg', 'Splice.DsoRules', 'DsoRules',
                          $dsoRulesCid, false, '{}'::jsonb, '{}'::jsonb, array[]::text[],
                          u.record_time, u.update_id, u.domain_id, u.migration_id
                   from scan_txlog_store t
                            join update_history_transactions_legacy u
                                 on u.history_id = $historyId
                                     and u.migration_id = t.migration_id
                                     and u.domain_id = t.domain_id
                                     and u.update_id = substring(t.event_id from '^#?(.*):[0-9]+$$')
                   where t.store_id = $storeId and t.entry_type = 'vot'
                   on conflict do nothing""",
          )
          .map(_ => ()),
        "test.insertTxLogVoteExercises",
      )
    ).map(_ => ())
  }

  private def setFirstIngestedRecordTime(
      stores: Stores,
      recordTime: CantonTimestamp,
  ): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.update(
        sqlu"""update key_value_store
               set value = to_jsonb(${recordTime.toMicros})
               where key = ${DbVotesStore.firstIngestedRecordTimeKey(stores.votesStore.storeId)}""",
        "test.setFirstIngestedRecordTime",
      )
    ).map(_ => ())

  /** Runs the migration script as is, to check it against the data written by the ingestion.
    * Flyway runs a script once, so this bypasses [[DbStorageIdempotency]], which runs all writes twice.
    */
  private def runTxLogMigration(): Future[Unit] = {
    val script = scala.io.Source
      .fromResource("db/migration/canton-network/postgres/stable/V084__votes_store_from_txlog.sql")
      .mkString
    val migration = onLegacyUpdateHistory(script)
    futureUnlessShutdownToFuture(
      storage.underlying.queryAndUpdate(sqlu"#$migration".map(_ => ()), "test.runTxLogMigration")
    )
  }

  private lazy val filters: Seq[VoteResultsFilters] = Seq(
    VoteResultsFilters(),
    VoteResultsFilters(accepted = Some(true)),
    VoteResultsFilters(accepted = Some(false)),
    VoteResultsFilters(actionName = Some("AddSv")),
    VoteResultsFilters(actionName = Some("SRARC_AddSv")),
    VoteResultsFilters(actionName = Some("UpdateSvRewardWeight")),
    VoteResultsFilters(actionName = Some("CRARC_MiningRound_Archive")),
    VoteResultsFilters(actionName = Some("DoesNotExist")),
    VoteResultsFilters(requester = Some(requesters(0).toProtoPrimitive)),
    VoteResultsFilters(requester = Some("user-2")),
    VoteResultsFilters(effectiveFrom = Some(base.plusSeconds(5).toString)),
    VoteResultsFilters(effectiveTo = Some(base.plusSeconds(15).toString)),
    VoteResultsFilters(
      effectiveFrom = Some(base.plusSeconds(5).toString),
      effectiveTo = Some(base.plusSeconds(40).toString),
    ),
    VoteResultsFilters(
      actionName = Some("AddSv"),
      accepted = Some(true),
      requester = Some(requesters(0).toProtoPrimitive),
    ),
    VoteResultsFilters(
      actionName = Some("UpdateSvRewardWeight"),
      accepted = Some(false),
      effectiveTo = Some(base.plusSeconds(15).toString),
    ),
  )

  private def closeVoteRequestEvent(result: DsoRules_CloseVoteRequestResult): Event =
    dsoRulesExercise(
      DsoRulesCloseVoteRequest.choice.name,
      mkCloseVoteRequest(result.request.trackingCid.toScala.getOrElse(fail("Missing cid"))),
      result.toValue,
    )

  private def dsoRulesExercise(
      choiceName: String,
      choiceArgument: com.daml.ledger.javaapi.data.Value,
      exerciseResult: com.daml.ledger.javaapi.data.Value,
  ): ExercisedEvent =
    new ExercisedEvent(
      Seq.empty.asJava,
      0,
      1,
      DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID,
      getPackageName(DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID),
      Optional.empty(),
      dsoRulesCid,
      choiceName,
      choiceArgument,
      Seq.empty.asJava,
      false,
      1,
      exerciseResult,
      Seq.empty.asJava,
      false,
    )

  private lazy val dsoRulesCid = nextCid()

  private def mkFixtureTx(fixture: VoteFixture)(offset: Long): Transaction =
    if (fixture.nested) {
      // A root exercise node that is not a vote closing, with the vote closings as its children
      val root = dsoRulesExercise(
        "DsoRules_SomeOtherChoice",
        new DamlRecord(),
        damlUnit.getInstance(),
      )
      mkExerciseTx(
        offset,
        root,
        fixture.votes.map(closeVoteRequestEvent),
        dummyDomain,
        effectiveAt = fixture.recordTime,
      )
    } else {
      mkTx(
        offset,
        fixture.votes.map(closeVoteRequestEvent),
        dummyDomain,
        effectiveAt = fixture.recordTime,
        recordTime = fixture.recordTime,
      )
    }

  private def listContractIds(): Future[Seq[(String, Option[String])]] =
    futureUnlessShutdownToFuture(
      storage.query(
        sql"select update_id, contract_id from votes_store_results".as[(String, Option[String])],
        "test.listContractIds",
      )
    )

  private def clearContractIds(): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.update(
        sqlu"update votes_store_results set contract_id = null",
        "test.clearContractIds",
      )
    ).map(_ => ())

  /** Returns the id of the update history of the given participant, creating it if there is none. */
  private def insertHistory(participantName: String, party: String): Future[Int] = {
    val participant = mkParticipantId(participantName).toProtoPrimitive
    futureUnlessShutdownToFuture(
      storage.underlying.queryAndUpdate(
        sql"""select id from update_history_descriptors
              where party = $party and participant_id = $participant and store_name = 'DbScanStore'"""
          .as[Int]
          .headOption
          .flatMap {
            case Some(id) => DBIO.successful(Some(id))
            case None =>
              sql"""insert into update_history_descriptors (party, participant_id, store_name)
                    values ($party, $participant, 'DbScanStore')
                    returning id""".as[Int].headOption
          },
        "test.insertHistory",
      )
    ).map(_.getOrElse(fail("No history inserted")))
  }

  /** Inserts an update with a single exercise on a DsoRules contract into the update history.
    * The row of the update is shared by all exercises of the same history.
    * Flyway-style single execution is needed here, as [[DbStorageIdempotency]] runs all writes twice.
    */
  private def insertExercise(
      historyId: Int,
      updateId: String,
      nodeId: Int,
      choice: String,
      contractId: String,
  ): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.underlying.queryAndUpdate(
        DBIO
          .seq(
            sqlu"""insert into update_history_transactions_legacy
                   (history_id, update_id, record_time, participant_offset, domain_id, migration_id,
                    effective_at, root_event_ids)
                 select $historyId, v.update_id, v.record_time, '0', v.synchronizer_id, $domainMigrationId,
                        v.record_time, array['#0']
                 from votes_store_results v
                 where v.update_id = $updateId
                   and not exists (select 1 from update_history_transactions_legacy t
                                   where t.history_id = $historyId and t.update_id = $updateId)
                 limit 1""",
            sqlu"""insert into update_history_exercises_legacy
                   (history_id, event_id, update_row_id, child_event_ids, choice, package_name, template_id_package_id,
                    template_id_module_name, template_id_entity_name, contract_id, consuming, argument, result, acting_parties,
                    record_time, update_id, domain_id, migration_id)
                 select $historyId, '#' || $updateId || ':' || $nodeId, t.row_id, array[]::text[], $choice, 'splice-dso-governance', 'pkg',
                        'Splice.DsoRules', 'DsoRules', $contractId, false, '{}'::jsonb, '{}'::jsonb, array[]::text[],
                        t.record_time, t.update_id, t.domain_id, t.migration_id
                 from update_history_transactions_legacy t
                 where t.history_id = $historyId and t.update_id = $updateId""",
          )
          .map(_ => ()),
        "test.insertExercise",
      )
    ).map(_ => ())

  /** Runs the migration script as is, to check it against the data written by the ingestion.
    * Flyway runs a script once, so this bypasses [[DbStorageIdempotency]], which runs all writes twice.
    * The column that the script adds is already there, so only its update statement is run.
    */
  private def runMigration(): Future[Unit] = {
    val script = scala.io.Source
      .fromResource("db/migration/canton-network/postgres/stable/V081__votes_store_contract_id.sql")
      .mkString
    val update = onLegacyUpdateHistory(
      script.substring(script.indexOf("update votes_store_results"))
    )
    futureUnlessShutdownToFuture(
      storage.underlying.queryAndUpdate(sqlu"#$update".map(_ => ()), "test.runMigration")
    )
  }

  private def onLegacyUpdateHistory(script: String): String =
    script.replaceAll("\\b(update_history_(transactions|exercises))\\b", "$1_legacy")

  private case class Stores(
      scanStore: ScanStore,
      votesStore: DbVotesStore,
  )

  private def ingestAll(stores: Stores, fixtures: Seq[VoteFixture]): Future[Unit] =
    MonadUtil
      .sequentialTraverse(fixtures)(fixture =>
        dummyDomain.ingestMulti(mkFixtureTx(fixture))(
          Seq(stores.scanStore.multiDomainAcsStore)
        )
      )
      .map(_ => ())

  /** Lists all results by following the page tokens. Returns the content of all pages.
    * Page tokens are not compared, as they are not expected to be the same for the two stores.
    */
  private def listAllPages(
      store: HistoricalVotesStore,
      filters: VoteResultsFilters,
      pageSize: Int,
  ): Future[Seq[Seq[DsoRules_CloseVoteRequestResult]]] = {
    def go(
        after: Option[Long],
        acc: Seq[Seq[DsoRules_CloseVoteRequestResult]],
    ): Future[Seq[Seq[DsoRules_CloseVoteRequestResult]]] =
      store
        .listVoteRequestResults(filters, PageLimit.tryCreate(pageSize), after)
        .flatMap { page =>
          if (acc.size > 100) fail(s"Too many pages for $filters")
          else if (page.resultsInPage.isEmpty) Future.successful(acc)
          else
            page.nextPageToken match {
              case None => Future.successful(acc :+ page.resultsInPage)
              case token => go(token, acc :+ page.resultsInPage)
            }
        }
    go(None, Seq.empty)
  }

  /** Pages backwards through a (fully backfilled) votes store via [[DbVotesStore.listBackfillPage]],
    * following the cursor the same way [[VotesStoreBackfilling]] would when reading from a peer.
    */
  private def listAllPeerPages(
      votesStore: DbVotesStore,
      pageSize: Int,
  ): Future[Seq[DbVotesStore.PeerVoteResult]] = {
    def go(
        before: DbVotesStore.VotesBackfillCursor,
        acc: Seq[DbVotesStore.ServedVoteResult],
    ): Future[Seq[DbVotesStore.ServedVoteResult]] =
      votesStore.listBackfillPage(before, PageLimit.tryCreate(pageSize)).flatMap {
        case Seq() => Future.successful(acc)
        case page =>
          val oldest = page.minBy(row => (row.recordTime, row.eventNumber))
          go(
            DbVotesStore.VotesBackfillCursor(
              oldest.recordTime,
              oldest.eventNumber,
            ),
            acc ++ page,
          )
      }
    go(DbVotesStore.VotesBackfillCursor(CantonTimestamp.MaxValue, 0), Seq.empty)
      .map(_.map(decodeServed))
  }

  /** Decodes what a peer served the way the client of the API does. */
  private def decodeServed(served: DbVotesStore.ServedVoteResult): DbVotesStore.PeerVoteResult =
    DbVotesStore
      .EncodedPeerVoteResult(
        served.recordTime,
        served.synchronizerId,
        served.updateId,
        served.eventNumber,
        served.contractId.getOrElse(fail(s"No contract id for ${served.updateId}")),
        served.choiceResult,
      )
      .decode

  private def mkFreshVotesStore(): Future[DbVotesStore] = {
    val participantId = mkParticipantId("DbVotesStoreTestFreshVotesStore")
    for {
      kvStore <- ScanKeyValueStore(dsoParty, participantId, storage, loggerFactory)
      fresh <- mkStoresWith(kvStore, "update_history_votes_store_test_fresh")
    } yield fresh.votesStore
  }

  private def mkStores(): Future[Stores] =
    ScanKeyValueStore(dsoParty, mkParticipantId("DbVotesStoreTest"), storage, loggerFactory)
      .flatMap(mkStoresWith(_))

  private def mkStoresWith(
      kvStore: DbKeyValueStore,
      participantName: String = participantName,
      acsStoreDescriptorUserVersion: Option[Long] = None,
      txLogStoreDescriptorUserVersion: Option[Long] = None,
  ): Future[Stores] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++
          DarResources.amuletNameService.all ++
          DarResources.dsoGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val participantId = mkParticipantId(participantName)
    for {
      scanStore <- DbScanStore.create(
        key = ScanStore.Key(dsoParty),
        storage,
        loggerFactory,
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
        domainMigrationId,
        synchronizerId = dummyDomain,
        participantId = participantId,
        IngestionConfig(),
        new DbScanStoreMetrics(new NoOpMetricsFactory(), loggerFactory, timeouts),
        defaultLimit = HardLimit.tryCreate(Limit.DefaultMaxPageSize),
        acsStoreDescriptorUserVersion,
        txLogStoreDescriptorUserVersion,
        additionalIngestionSinks = Seq(new DbVotesStore.IngestionSink(kvStore, loggerFactory)),
      )(parallelExecutionContext, implicitly, implicitly, implicitly)
      votesStore = DbVotesStore(
        TxLogStoreId.unwrap(scanStore.txLogStoreId),
        storage,
        kvStore,
        loggerFactory,
      )
      initializeResult <- scanStore.multiDomainAcsStore.testIngestionSink.initialize(
        mockLedgerConnection()
      )
      _ <- initializeResult match {
        case ResumeAtOffset(_) | UpdateHistoryInitAtLatestPrunedOffset => Future.unit
        case InitializeAcsAtLatestOffset | InitializeAcsAtOffset(_) =>
          scanStore.multiDomainAcsStore.testIngestionSink.ingestAcs(
            nextOffset(),
            Seq.empty,
          )
      }
      _ <- scanStore.domains.ingestionSink.ingestConnectedDomains(
        Map(SynchronizerAlias.tryCreate(dummyDomain.toProtoPrimitive) -> dummyDomain)
      )
    } yield Stores(scanStore, votesStore)
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.votesbackfilling

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.apache.pekko.stream.scaladsl.Sink
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ExtActionRequiringConformation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.voterequestoutcome.VRO_Expired
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequestResult,
  Reason,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore.{
  PeerVoteResult,
  UndecodablePeerVoteResult,
  VotesBackfillCursor,
}
import org.lfdecentralizedtrust.splice.scan.store.votesbackfilling.VotesStoreBackfilling.*
import org.scalatest.wordspec.AsyncWordSpec
import org.slf4j.event.Level

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

class VotesStoreBackfillingTest
    extends AsyncWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem {

  private def ts(seconds: Int): CantonTimestamp = CantonTimestamp.ofEpochSecond(seconds.toLong)

  private def cursor(seconds: Int, eventNumber: Int = 0): VotesBackfillCursor =
    VotesBackfillCursor(ts(seconds), eventNumber)

  /** The backfilling only passes votes on, so they only differ in what identifies them. */
  private def vote(seconds: Int, eventNumber: Int = 0): PeerVoteResult = {
    val at = ts(seconds).toInstant
    PeerVoteResult(
      ts(seconds),
      "synchronizer",
      s"update-$seconds",
      eventNumber,
      "dso-rules",
      new DsoRules_CloseVoteRequestResult(
        new VoteRequest(
          "requester",
          "dso",
          new ExtActionRequiringConformation(com.daml.ledger.javaapi.data.Unit.getInstance()),
          new Reason("", ""),
          at,
          java.util.Map.of(),
          java.util.Optional.empty(),
          java.util.Optional.empty(),
        ),
        at,
        java.util.List.of(),
        java.util.List.of(),
        new VRO_Expired(com.daml.ledger.javaapi.data.Unit.getInstance()),
      ),
    )
  }

  private class FakeStore(
      firstIngested: Option[CantonTimestamp],
      initialCursor: Option[VotesBackfillCursor] = None,
      initiallyComplete: Boolean = false,
  ) extends VotesBackfillingStore {
    val cursorRef = new AtomicReference(initialCursor)
    val completions = new AtomicInteger(if (initiallyComplete) 1 else 0)
    val inserted = new AtomicReference(Vector.empty[(Seq[PeerVoteResult], VotesBackfillCursor)])

    override def getFirstIngestedRecordTime()(implicit tc: TraceContext) =
      Future.successful(firstIngested)
    override def getBackfillingCursor()(implicit tc: TraceContext) =
      Future.successful(cursorRef.get())
    override def insertPeerVoteResults(rows: Seq[PeerVoteResult], cursor: VotesBackfillCursor)(
        implicit tc: TraceContext
    ) = {
      inserted.updateAndGet(_ :+ (rows -> cursor))
      cursorRef.set(Some(cursor))
      Future.unit
    }
    override def isBackfillingComplete()(implicit tc: TraceContext) =
      Future.successful(completions.get() > 0)
    override def markBackfillingComplete()(implicit tc: TraceContext) = {
      completions.incrementAndGet()
      Future.unit
    }
  }

  /** Answers the requests in order. Fails once the script is used up, like peers that cannot serve yet. */
  private class ScriptedPeers(script: Try[Seq[PeerVoteResult]]*) extends PeerVotesSource {
    private val remaining = new AtomicReference(script.toList)
    val calls = new AtomicReference(Vector.empty[(VotesBackfillCursor, Int)])

    override def votesBefore(cursor: VotesBackfillCursor, count: Int)(implicit
        tc: TraceContext
    ) = {
      calls.updateAndGet(_ :+ (cursor -> count))
      remaining.getAndUpdate(_.drop(1)).headOption match {
        case Some(response) => Future.fromTry(response)
        case None => Future.failed(new IllegalStateException("peers cannot serve yet"))
      }
    }
  }

  private val synchronizerId = SynchronizerId.tryFromString("synchronizer::id")

  /** An update history that starts with the founding transaction at `foundingSeconds`. */
  private def fromGenesis(foundingSeconds: Int, ingestedUpTo: Option[Int]) =
    LocalVotesSource.FromGenesis(synchronizerId, ts(foundingSeconds), ingestedUpTo.map(ts))

  /** Answers `historyStart` in order, staying at the last answer, and the page requests in order. */
  private class ScriptedLocal(
      starts: Seq[LocalVotesSource.HistoryStart],
      pages: Try[LocalVotesSource.Page]*
  ) extends LocalVotesSource {
    private val remainingStarts = new AtomicReference(starts.toList)
    private val remainingPages = new AtomicReference(pages.toList)
    val calls = new AtomicReference(Vector.empty[(CantonTimestamp, Int)])

    override def historyStart()(implicit tc: TraceContext) =
      Future.successful(
        remainingStarts.getAndUpdate(s => if (s.sizeIs > 1) s.drop(1) else s).headOption.value
      )

    override def votesBefore(
        start: LocalVotesSource.FromGenesis,
        before: CantonTimestamp,
        count: Int,
    )(implicit tc: TraceContext) = {
      calls.updateAndGet(_ :+ (before -> count))
      remainingPages.getAndUpdate(_.drop(1)).headOption match {
        case Some(response) => Future.fromTry(response)
        case None => Future.failed(new IllegalStateException("no more pages scripted"))
      }
    }
  }

  private def page(oldestSeconds: Int, votes: PeerVoteResult*) =
    Success(LocalVotesSource.Page(votes, Some(ts(oldestSeconds))))
  private val noOlderUpdates = Success(LocalVotesSource.Page(Seq.empty, None))

  private val notFromGenesis = new ScriptedLocal(Seq(LocalVotesSource.NotFromGenesis))

  private def service(
      store: FakeStore,
      peers: PeerVotesSource,
      local: LocalVotesSource = notFromGenesis,
      pageSize: Int = 2,
      maxLocalHistorySeconds: Int = 1000,
  ) =
    new VotesStoreBackfilling(
      NonNegativeFiniteDuration.ofMillis(10),
      pageSize,
      NonNegativeFiniteDuration.ofSeconds(maxLocalHistorySeconds.toLong),
      local,
      peers,
      store,
      loggerFactory,
    )

  "VotesStoreBackfilling" should {

    "copy from the update history if it starts with the founding transaction, without asking peers" in {
      val store = new FakeStore(Some(ts(100)))
      val peers = new ScriptedPeers()
      val local = new ScriptedLocal(
        Seq(fromGenesis(10, ingestedUpTo = Some(100))),
        page(60, vote(90), vote(70, eventNumber = 2)),
        // A page of updates without votes still moves the cursor
        page(10),
        noOlderUpdates,
      )
      service(store, peers, local).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(
          Started(cursor(100)),
          LocalPageBackfilled(2, cursor(60)),
          LocalPageBackfilled(0, cursor(10)),
          Completed,
        )
        local.calls.get() shouldBe Vector(ts(100) -> 2, ts(60) -> 2, ts(10) -> 2)
        store.inserted.get() shouldBe Vector(
          Seq(vote(90), vote(70, eventNumber = 2)) -> cursor(60),
          Seq.empty -> cursor(10),
        )
        store.completions.get() shouldBe 1
        peers.calls.get() shouldBe empty
      }
    }

    "complete on a new network, where the update history has nothing before the votes store started" in {
      val store = new FakeStore(Some(ts(100)))
      val local = new ScriptedLocal(Seq(fromGenesis(100, ingestedUpTo = Some(100))), noOlderUpdates)
      service(store, new ScriptedPeers(), local).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(Started(cursor(100)), Completed)
        store.inserted.get() shouldBe empty
        store.completions.get() shouldBe 1
      }
    }

    "backfill from peers if the founding transaction is too long ago to read the update history" in {
      val store = new FakeStore(Some(ts(100)))
      val peers = new ScriptedPeers(Success(Seq.empty))
      val local = new ScriptedLocal(Seq(fromGenesis(10, ingestedUpTo = Some(100))))
      service(store, peers, local, maxLocalHistorySeconds = 89).mksrc().runWith(Sink.seq).map {
        steps =>
          steps shouldBe Seq(Started(cursor(100)), Completed)
          peers.calls.get().map(_._1) shouldBe Vector(cursor(100))
          local.calls.get() shouldBe empty
      }
    }

    "wait until the update history has ingested up to where the votes store started" in {
      val store = new FakeStore(Some(ts(100)))
      val local = new ScriptedLocal(
        Seq(
          LocalVotesSource.NotIngested,
          fromGenesis(10, ingestedUpTo = None),
          fromGenesis(10, ingestedUpTo = Some(99)),
          fromGenesis(10, ingestedUpTo = Some(100)),
        ),
        noOlderUpdates,
      )
      service(store, new ScriptedPeers(), local).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(
          WaitingForUpdateHistory,
          WaitingForUpdateHistory,
          WaitingForUpdateHistory,
          Started(cursor(100)),
          Completed,
        )
      }
    }

    "read a transaction again if the persisted cursor is in the middle of it" in {
      val store = new FakeStore(Some(ts(100)), initialCursor = Some(cursor(50, 3)))
      val local = new ScriptedLocal(Seq(fromGenesis(10, ingestedUpTo = Some(100))), noOlderUpdates)
      service(store, new ScriptedPeers(), local).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(Resumed(cursor(50, 3)), Completed)
        local.calls.get().map(_._1) shouldBe Vector(ts(50).immediateSuccessor)
      }
    }

    "warn and read again at the same cursor if a vote result in the update history cannot be decoded" in {
      val store = new FakeStore(Some(ts(100)))
      val local = new ScriptedLocal(
        Seq(fromGenesis(10, ingestedUpTo = Some(100))),
        Failure(new UndecodablePeerVoteResult("the-update", ts(90), new RuntimeException("boom"))),
        noOlderUpdates,
      )
      loggerFactory
        .assertLogs(
          service(store, new ScriptedPeers(), local).mksrc().runWith(Sink.seq),
          _.warningMessage should (include("stuck") and include("the-update")),
        )
        .map { steps =>
          steps shouldBe Seq(Started(cursor(100)), WaitingForUpdateHistory, Completed)
          local.calls.get().map(_._1) shouldBe Vector(ts(100), ts(100))
        }
    }

    "do nothing once complete" in {
      val store = new FakeStore(Some(ts(100)), initiallyComplete = true)
      val peers = new ScriptedPeers()
      val local = new ScriptedLocal(Seq(fromGenesis(10, ingestedUpTo = Some(100))))
      for {
        fromPeers <- service(store, peers).mksrc().runWith(Sink.seq)
        fromLocal <- service(store, peers, local).mksrc().runWith(Sink.seq)
      } yield {
        fromPeers shouldBe empty
        fromLocal shouldBe empty
        store.completions.get() shouldBe 1
        peers.calls.get() shouldBe empty
        local.calls.get() shouldBe empty
      }
    }

    "wait until the votes store has ingested a transaction" in {
      val store = new FakeStore(firstIngested = None)
      val peers = new ScriptedPeers()
      service(store, peers).mksrc().take(3).runWith(Sink.seq).map { steps =>
        steps shouldBe Seq.fill(3)(WaitingForLiveIngestion)
        store.completions.get() shouldBe 0
        peers.calls.get() shouldBe empty
      }
    }

    "copy pages from the peers, starting at the first ingested record time, until they have nothing older" in {
      val store = new FakeStore(Some(ts(100)))
      val peers = new ScriptedPeers(
        Success(Seq(vote(90), vote(80))),
        Success(Seq(vote(70))),
        Success(Seq.empty),
      )
      service(store, peers, pageSize = 2).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(
          Started(cursor(100)),
          PeerPageBackfilled(2, cursor(80)),
          PeerPageBackfilled(1, cursor(70)),
          Completed,
        )
        peers.calls.get() shouldBe Vector(cursor(100) -> 2, cursor(80) -> 2, cursor(70) -> 2)
        store.inserted.get() shouldBe Vector(
          Seq(vote(90), vote(80)) -> cursor(80),
          Seq(vote(70)) -> cursor(70),
        )
        store.completions.get() shouldBe 1
      }
    }

    "continue before the oldest (record time, event number) of a page" in {
      val store = new FakeStore(Some(ts(100)))
      val peers = new ScriptedPeers(
        Success(Seq(vote(90, eventNumber = 2), vote(90, eventNumber = 1))),
        Success(Seq.empty),
      )
      service(store, peers).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(
          Started(cursor(100)),
          PeerPageBackfilled(2, cursor(90, 1)),
          Completed,
        )
        peers.calls.get().map(_._1) shouldBe Vector(cursor(100), cursor(90, 1))
      }
    }

    "resume from the persisted cursor, not from the first ingested record time" in {
      val store = new FakeStore(Some(ts(100)), initialCursor = Some(cursor(50, 3)))
      val peers = new ScriptedPeers(Success(Seq.empty))
      service(store, peers).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(Resumed(cursor(50, 3)), Completed)
        peers.calls.get().map(_._1) shouldBe Vector(cursor(50, 3))
        store.inserted.get() shouldBe empty
        store.completions.get() shouldBe 1
      }
    }

    "wait and ask again at the same cursor if the peers cannot serve yet" in {
      val store = new FakeStore(Some(ts(100)))
      val peers = new ScriptedPeers(
        Failure(new IllegalStateException("peers are still backfilling")),
        Failure(new IllegalStateException("peers are still backfilling")),
        Success(Seq(vote(90))),
        Success(Seq.empty),
      )
      service(store, peers).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(
          Started(cursor(100)),
          WaitingForPeers(cursor(100)),
          WaitingForPeers(cursor(100)),
          PeerPageBackfilled(1, cursor(90)),
          Completed,
        )
        peers.calls.get().map(_._1) shouldBe
          Vector(cursor(100), cursor(100), cursor(100), cursor(90))
        store.completions.get() shouldBe 1
      }
    }

    "warn and ask again at the same cursor if a vote result cannot be decoded" in {
      val store = new FakeStore(Some(ts(100)))
      def undecodable =
        Failure(new UndecodablePeerVoteResult("the-update", ts(90), new RuntimeException("boom")))
      val peers = new ScriptedPeers(undecodable, undecodable, Success(Seq.empty))
      loggerFactory
        .assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          service(store, peers).mksrc().runWith(Sink.seq),
          entries => {
            entries should have size 2
            forAll(entries) { entry =>
              entry.level shouldBe Level.WARN
              entry.message should (include("stuck") and include("the-update"))
            }
          },
        )
        .map { steps =>
          steps shouldBe Seq(
            Started(cursor(100)),
            WaitingForPeers(cursor(100)),
            WaitingForPeers(cursor(100)),
            Completed,
          )
          store.inserted.get() shouldBe empty
        }
    }

    "not complete while the peers cannot serve" in {
      val store = new FakeStore(Some(ts(100)))
      service(store, new ScriptedPeers()).mksrc().take(4).runWith(Sink.seq).map { steps =>
        steps shouldBe Started(cursor(100)) +: Seq.fill(3)(WaitingForPeers(cursor(100)))
        store.completions.get() shouldBe 0
        store.inserted.get() shouldBe empty
      }
    }

    "keep the service stream open after completion, so the retrying service does not restart it" in {
      val store = new FakeStore(Some(ts(100)))
      service(store, new ScriptedPeers(Success(Seq.empty)))
        .serviceSource()
        .completionTimeout(300.millis)
        .runWith(Sink.ignore)
        .failed
        .map { failure =>
          failure shouldBe a[java.util.concurrent.TimeoutException]
          store.completions.get() shouldBe 1
        }
    }
  }
}

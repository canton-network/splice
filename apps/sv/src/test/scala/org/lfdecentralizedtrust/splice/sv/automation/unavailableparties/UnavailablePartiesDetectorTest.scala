// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.unavailableparties

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.error.MediatorError
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import com.google.rpc.{Code, ErrorInfo, Status}
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.StatusProto
import org.lfdecentralizedtrust.splice.store.{StoreTestBase, UnavailablePartiesStore}
import org.lfdecentralizedtrust.splice.store.db.{
  DbUnavailablePartiesStore,
  SplicePostgresTest,
  StoreDescriptor,
}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.concurrent.duration.*

class UnavailablePartiesDetectorTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest {

  import UnavailablePartiesDetectorTest.Contract

  private def party(name: String): PartyId =
    PartyId.tryFromProtoPrimitive(s"$name::1220deadbeef")

  private val dso = party("dso")
  private val alice = party("alice")
  private val bob = party("bob")
  private val charlie = party("charlie")

  private val v1 = PackageVersion.assertFromString("0.1.0")
  private val v2 = PackageVersion.assertFromString("0.2.0")

  private val storeDescriptor = StoreDescriptor(
    version = 1,
    name = "UnavailablePartiesDetectorTest",
    party = dsoParty,
    participant = mkParticipantId("participant"),
    key = Map(),
  )

  private val ignoreDuration = NonNegativeFiniteDuration(1.hour)

  private def mkStore(
      initialParties: Set[PartyId] = Set.empty
  ): Future[DbUnavailablePartiesStore] =
    for {
      store <- DbUnavailablePartiesStore(
        storeDescriptor,
        storage,
        ignoreDuration,
        ignoreDuration,
        wallClock,
        loggerFactory,
      )
      _ <- store.addParties(initialParties.toSeq)
    } yield store

  private def submissionFailure(
      reason: String = "PACKAGE_NOT_VETTED_BY_RECIPIENTS",
      message: String = "failure",
  ): StatusRuntimeException =
    StatusProto.toStatusRuntimeException(
      Status
        .newBuilder()
        .setCode(Code.FAILED_PRECONDITION_VALUE)
        .setMessage(message)
        .addDetails(
          com.google.protobuf.Any.pack(
            ErrorInfo
              .newBuilder()
              .setReason(reason)
              .build()
          )
        )
        .build()
    )

  /** A mediator timeout, which reports the parties that did not respond. */
  private def unresponsiveParties(parties: PartyId*): StatusRuntimeException =
    StatusProto.toStatusRuntimeException(
      Status
        .newBuilder()
        .setCode(Code.ABORTED_VALUE)
        .setMessage("timeout")
        .addDetails(
          com.google.protobuf.Any.pack(
            ErrorInfo
              .newBuilder()
              .setReason(MediatorError.Timeout.id)
              .putMetadata("unresponsiveParties", parties.map(_.toProtoPrimitive).mkString(","))
              .build()
          )
        )
        .build()
    )

  private def splitting(
      store: UnavailablePartiesStore,
      versions: Map[PartyId, Option[PackageVersion]],
      protectedParties: Set[PartyId] = Set(dso),
  ) =
    new UnavailablePartiesDetector(
      p => _ => Future.successful(versions.getOrElse(p, Some(v2))),
      store,
      protectedParties,
      loggerFactory,
    )

  private def run(
      contracts: Seq[Contract],
      versions: Map[PartyId, Option[PackageVersion]],
      failingContracts: Set[String],
      store: Future[DbUnavailablePartiesStore] = mkStore(),
      protectedParties: Set[PartyId] = Set(dso),
      failure: => StatusRuntimeException = submissionFailure(),
  ): Future[
    (UnavailablePartiesDetector.Result, Seq[Seq[String]], DbUnavailablePartiesStore)
  ] = store.flatMap { store =>
    val submissions = new AtomicReference[Seq[Seq[String]]](Seq.empty)
    splitting(store, versions, protectedParties)
      .processBatch[Contract](
        contracts,
        _.parties,
        batch => {
          submissions.updateAndGet(_ :+ batch.map(_.id))
          if (batch.exists(c => failingContracts.contains(c.id)))
            Future.failed(failure)
          else Future.unit
        },
      )
      .map(result => (result, submissions.get(), store))
  }

  "UnavailablePartiesDetector" should {

    "submit the full batch if it succeeds" in {
      val contracts =
        Seq(Contract("c1", Set(alice, dso)), Contract("c2", Set(bob, dso)))
      run(contracts, Map.empty, Set.empty).map { case (result, submissions, store) =>
        result.submittedTasks shouldBe 2
        result.splits shouldBe 0
        submissions should have size 1
        store.listParties().futureValue shouldBe empty
      }
    }

    "isolate the failing contract by splitting the batch and ignore the party on the lowest version" in {
      val contracts = Seq(
        Contract("c1", Set(alice, dso)),
        Contract("c2", Set(bob, dso)),
        Contract("c3", Set(bob, charlie, dso)),
        Contract("c4", Set(alice, dso)),
      )
      // charlie is on a old buggy version and makes c3 fail
      val versions =
        Map(alice -> Some(v2), bob -> Some(v2), charlie -> Some(v1), dso -> Some(v2))
      run(contracts, versions, Set("c3")).map { case (result, submissions, store) =>
        // [c3,c1,c2,c4] -> [c3,c1] + [c2,c4], then [c3,c1] -> [c3] + [c1]
        result.splits shouldBe 2
        result.failedTasks shouldBe 1
        result.submittedTasks shouldBe 3
        result.ignoredParties shouldBe Set(charlie)
        store.listParties().futureValue.toSet shouldBe Set(charlie)
        // the failing contract is tried first since it has the lowest version,
        // and the halves are retried depth-first, left before right
        submissions shouldBe Seq(
          Seq("c3", "c1", "c2", "c4"),
          Seq("c3", "c1"),
          Seq("c3"),
          Seq("c1"),
          Seq("c2", "c4"),
        )
      }
    }

    "pre-sort the batch ascending by lowest supported version, unknown versions first" in {
      val contracts = Seq(
        Contract("c1", Set(alice, dso)),
        Contract("c2", Set(bob, dso)),
        Contract("c3", Set(charlie, dso)),
      )
      val versions = Map(alice -> Some(v2), bob -> None, charlie -> Some(v1), dso -> Some(v2))
      run(contracts, versions, Set.empty).map { case (_, submissions, _) =>
        submissions shouldBe Seq(Seq("c2", "c3", "c1"))
      }
    }

    "over-approximate if all parties are on the same version" in {
      val contracts = Seq(Contract("c1", Set(alice, bob, dso)))
      run(contracts, Map.empty, Set("c1")).map { case (result, _, store) =>
        result.ignoredParties shouldBe Set(alice, bob)
        store.listParties().futureValue.toSet shouldBe Set(alice, bob)
      }
    }

    "fail the batch instead of ignoring protected parties" in {
      val contracts = Seq(Contract("c1", Set(alice, dso)))
      loggerFactory
        .assertLogs(
          run(
            contracts,
            Map(alice -> Some(v1), dso -> Some(v2)),
            Set("c1"),
            protectedParties = Set(dso, alice),
          ).failed,
          _.warningMessage should include("all candidates"),
        )
        .flatMap(_ => mkStore())
        .flatMap(_.listParties())
        .map(_ shouldBe empty)
    }

    "not submit contracts of parties ignored while processing the batch" in {
      val contracts = Seq(
        Contract("c1", Set(charlie, dso)),
        Contract("c2", Set(charlie, dso)),
      )
      val versions = Map(charlie -> Some(v1), dso -> Some(v2))
      run(contracts, versions, Set("c1")).map { case (result, submissions, _) =>
        result.ignoredParties shouldBe Set(charlie)
        // c2 is only submitted as part of the initial batch, never again once charlie is ignored
        submissions.count(_ == Seq("c2")) shouldBe 0
      }
    }

    "not load the ignore list from the store, relying on the fetched batch being filtered" in {
      val contracts = Seq(
        Contract("c1", Set(alice, dso)),
        Contract("c2", Set(charlie, dso)),
      )
      run(contracts, Map.empty, Set.empty, mkStore(Set(charlie))).map {
        case (result, submissions, _) =>
          result.submittedTasks shouldBe 2
          submissions shouldBe Seq(Seq("c1", "c2"))
      }
    }

    "ignore the parties reported as unresponsive without splitting" in {
      mkStore().flatMap { store =>
        val submissions = new AtomicReference[Seq[Seq[String]]](Seq.empty)
        val contracts = Seq(
          Contract("c1", Set(alice, dso)),
          Contract("c2", Set(bob, dso)),
          Contract("c3", Set(charlie, dso)),
        )
        splitting(store, Map.empty)
          .processBatch[Contract](
            contracts,
            _.parties,
            batch => {
              submissions.updateAndGet(_ :+ batch.map(_.id))
              if (batch.exists(_.id == "c3")) Future.failed(unresponsiveParties(charlie))
              else Future.unit
            },
          )
          .map { result =>
            // the mediator names the culprit, so there is no need to bisect the batch
            result.splits shouldBe 0
            result.ignoredParties shouldBe Set(charlie)
            result.failedTasks shouldBe 1
            result.submittedTasks shouldBe 2
            store.listParties().futureValue.toSet shouldBe Set(charlie)
            // the contracts that do not involve charlie are retried right away
            submissions.get() shouldBe Seq(Seq("c1", "c2", "c3"), Seq("c1", "c2"))
          }
      }
    }

    UnavailablePartiesDetector.VettingFailure.vettingErrorIds.foreach { errorId =>
      s"split and ignore the lowest-version party on $errorId" in {
        val contracts =
          Seq(Contract("c1", Set(alice, dso)), Contract("c2", Set(bob, dso)))
        run(
          contracts,
          Map(alice -> Some(v1)),
          Set("c1"),
          failure = submissionFailure(reason = errorId),
        ).map { case (result, _, store) =>
          result.splits shouldBe 1
          result.submittedTasks shouldBe 1
          store.listParties().futureValue.toSet shouldBe Set(alice)
        }
      }
    }

    "fail the whole batch without ignoring anyone on unrelated errors" in {
      val contracts =
        Seq(Contract("c1", Set(alice, dso)), Contract("c2", Set(bob, dso)))
      run(
        contracts,
        Map(alice -> Some(v1)),
        Set("c1"),
        failure = submissionFailure(reason = "CONTRACT_NOT_ACTIVE"),
      ).failed.flatMap(_ => mkStore()).flatMap(_.listParties()).map(_ shouldBe empty)
    }

    "split on NO_SYNCHRONIZER_FOR_SUBMISSION caused by unknown packages" in {
      val contracts =
        Seq(Contract("c1", Set(alice, dso)), Contract("c2", Set(bob, dso)))
      run(
        contracts,
        Map(alice -> Some(v1)),
        Set("c1"),
        failure = submissionFailure(
          reason = "NO_SYNCHRONIZER_FOR_SUBMISSION",
          message =
            s"No valid synchronizer for submission found. Discarded synchronizers: sync::1: ${UnavailablePartiesDetector.VettingFailure.unknownPackageRoutingMessage} on synchronizer sync::1",
        ),
      ).map { case (result, _, store) =>
        result.splits shouldBe 1
        store.listParties().futureValue.toSet shouldBe Set(alice)
      }
    }

    "fail the whole batch on NO_SYNCHRONIZER_FOR_SUBMISSION for other reasons" in {
      val contracts =
        Seq(Contract("c1", Set(alice, dso)), Contract("c2", Set(bob, dso)))
      run(
        contracts,
        Map(alice -> Some(v1)),
        Set("c1"),
        failure = submissionFailure(
          reason = "NO_SYNCHRONIZER_FOR_SUBMISSION",
          message =
            "No valid synchronizer for submission found. Discarded synchronizers: sync::1: Parties Set(alice) don't have an active participant on synchronizer sync::1",
        ),
      ).failed.flatMap(_ => mkStore()).flatMap(_.listParties()).map(_ shouldBe empty)
    }

    "stay stack safe when isolating thousands of failing tasks" in {
      val n = 5000
      val contracts = (0 until n).map(i => Contract(s"c$i", Set(party(s"p$i"))))
      run(contracts, Map.empty, contracts.map(_.id).toSet).map { case (result, _, _) =>
        result.splits shouldBe n - 1
        result.failedTasks shouldBe n
      }
    }

  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}

object UnavailablePartiesDetectorTest {
  final case class Contract(id: String, parties: Set[PartyId])
}

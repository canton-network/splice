// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.sponsor

import better.files.File
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.PositiveFiniteDuration
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.{PartyId, SequencerId, SynchronizerId, UniqueIdentifier}
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import io.grpc.{Status, StatusRuntimeException}
import org.lfdecentralizedtrust.splice.automation.{TriggerContext, TriggerEnabledSynchronization}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.sv.automation.SvOnboardingSnapshotCleanupTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotId
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService.{
  SnapshotKey,
  SnapshotState,
}
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.{Duration, Instant}
import java.util.{Base64, UUID}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.{Future, Promise, blocking}

class SvOnboardingSnapshotServiceTest
    extends AnyWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext {

  private val sha256Base64 =
    Base64.getEncoder.encodeToString(
      MessageDigest.getInstance("SHA-256").digest("snapshot".getBytes)
    )

  private def key(name: String) =
    SnapshotKey.Acs(
      SynchronizerId.tryFromString("synchronizer::dummy"),
      PartyId.tryFromProtoPrimitive(s"$name::dummy"),
      Instant.EPOCH,
    )

  private def sequencerKey(name: String) = SnapshotKey.SequencerOnboardingState(
    SynchronizerId.tryFromString("synchronizer::dummy"),
    SequencerId(UniqueIdentifier.tryFromProtoPrimitive(s"$name::dummy")),
  )

  private def writeSnapshot(result: Future[Unit]): Path => Future[Unit] = file => {
    Files.write(file, "snapshot".getBytes)
    result
  }

  private def preventDeletion(file: Path): Path = {
    Files.deleteIfExists(file)
    Files.createDirectory(file)
    Files.write(file.resolve("block-deletion"), Array[Byte](1))
  }

  private def unavailable(lookup: => Option[SnapshotState]) =
    (the[StatusRuntimeException] thrownBy lookup).getStatus.getCode shouldBe Status.Code.UNAVAILABLE

  private class FailingClock extends SimClock(loggerFactory = loggerFactory) {
    val failing = new AtomicBoolean(false)
    override def now: CantonTimestamp =
      if (failing.get()) throw new RuntimeException("clock failed") else super.now
  }

  private def withService(
      config: Path => SvOnboardingSnapshotsConfig = SvOnboardingSnapshotsConfig(_),
      clock: SimClock = new SimClock(loggerFactory = loggerFactory),
  )(
      test: (SvOnboardingSnapshotService, SimClock, RetryProvider) => Unit
  ): Unit = File.usingTemporaryDirectory() { directory =>
    val retryProvider =
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)
    val service =
      new SvOnboardingSnapshotService(config(directory.path), clock, retryProvider, loggerFactory)
    try test(service, clock, retryProvider)
    finally {
      retryProvider.close()
      service.close()
    }
  }

  "SvOnboardingSnapshotService" should {

    "export in the background and reuse the snapshot for the same key" in withService() {
      (service, _, _) =>
        val exported = Promise[Unit]()
        val exports = new AtomicInteger()
        def prepare(name: String) = service
          .prepare(
            key(name),
            file => {
              exports.incrementAndGet()
              writeSnapshot(exported.future)(file)
            },
          )
          .futureValue

        val id = prepare("sv1")
        eventually()(service.lookup(id) should matchPattern {
          case Some(SnapshotState.Exporting(_, _)) =>
        })
        prepare("sv1") shouldBe id

        exported.success(())
        val file = eventually() {
          inside(service.lookup(id)) { case Some(SnapshotState.Ready(file, digest)) =>
            digest shouldBe sha256Base64
            file
          }
        }
        File(file).contentAsString shouldBe "snapshot"
        Files.exists(file.getParent.resolve("metadata.json")) shouldBe true
        prepare("sv1") shouldBe id
        exports.get() shouldBe 1

        prepare("sv2") should not be id
        eventually()(exports.get() shouldBe 2)
    }

    "report queued and running exports with the times from the service clock" in withService() {
      (service, clock, _) =>
        val first = Promise[Unit]()
        val second = Promise[Unit]()
        try {
          val queuedAt = clock.now
          val running = service.prepare(key("sv1"), writeSnapshot(first.future)).futureValue
          eventually()(
            service.lookup(running) shouldBe Some(SnapshotState.Exporting(queuedAt, queuedAt))
          )

          clock.advance(Duration.ofMinutes(1))
          val waitingSince = clock.now
          val waiting = service.prepare(key("sv2"), writeSnapshot(second.future)).futureValue
          service.lookup(waiting) shouldBe Some(SnapshotState.Queued(waitingSince))

          clock.advance(Duration.ofMinutes(1))
          first.success(())
          eventually()(
            service.lookup(waiting) shouldBe Some(
              SnapshotState.Exporting(waitingSince, clock.now)
            )
          )
        } finally Seq(first, second).foreach(_.trySuccess(()))
    }

    "start a new export after a failed one" in File.usingTemporaryDirectory() { directory =>
      withService(_ => SvOnboardingSnapshotsConfig(directory.path)) { (service, _, _) =>
        val failedId =
          loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
            {
              val id = service
                .prepare(
                  key("sv1"),
                  writeSnapshot(Future.failed(new RuntimeException("boom"))),
                )
                .futureValue
              eventually()(
                service.lookup(id) shouldBe Some(SnapshotState.Failed("Snapshot export failed"))
              )
              id
            },
            forExactly(1, _)(_.warningMessage should include("failed")),
          )
        (directory / "exports" / failedId.value).list
          .map(_.name)
          .toList shouldBe List("failure.json")

        val id =
          service.prepare(key("sv1"), writeSnapshot(Future.unit)).futureValue
        id should not be failedId
        eventually()(service.lookup(id) should matchPattern {
          case Some(SnapshotState.Ready(_, _)) =>
        })
      }
    }

    "record an export whose file cannot be hashed as failed and start the next one" in withService() {
      (service, _, _) =>
        val failedId =
          loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
            {
              val id = service
                .prepare(
                  key("sv1"),
                  file => {
                    Files.createDirectory(file)
                    Future.unit
                  },
                )
                .futureValue
              eventually()(
                service.lookup(id) shouldBe Some(SnapshotState.Failed("Snapshot export failed"))
              )
              id
            },
            forExactly(1, _)(_.warningMessage should include("failed")),
          )

        val id = service.prepare(key("sv1"), writeSnapshot(Future.unit)).futureValue
        id should not be failedId
        eventually()(service.lookup(id) should matchPattern {
          case Some(SnapshotState.Ready(_, `sha256Base64`)) =>
        })
    }

    "prepare a new snapshot if the ready file was deleted" in withService() { (service, _, _) =>
      val snapshotKey = key("sv1")
      val id = service.prepare(snapshotKey, writeSnapshot(Future.unit)).futureValue
      val file = eventually() {
        inside(service.lookup(id)) { case Some(SnapshotState.Ready(file, _)) => file }
      }
      Files.delete(file)

      val replacement =
        service.prepare(snapshotKey, writeSnapshot(Future.unit)).futureValue
      replacement should not be id
      loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(Level.WARN))(
        service.lookup(id) shouldBe Some(SnapshotState.Failed("Snapshot file is missing")),
        _.warningMessage should include(s"Onboarding snapshot $id is unusable"),
      )
      service.lookup(id) shouldBe Some(SnapshotState.Failed("Snapshot file is missing"))
      Files.exists(file.resolveSibling("metadata.json")) shouldBe false
      eventually()(service.lookup(replacement) should matchPattern {
        case Some(SnapshotState.Ready(_, _)) =>
      })
      service
        .prepare(snapshotKey, writeSnapshot(Future.unit))
        .futureValue shouldBe replacement
    }

    "keep a failed export for the retention period and log its removal once" in withService() {
      (service, clock, _) =>
        val failed = SnapshotState.Failed("Snapshot export failed")
        val failedId =
          loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
            {
              val id = service
                .prepare(
                  key("sv1"),
                  writeSnapshot(Future.failed(new RuntimeException("export failed"))),
                )
                .futureValue
              eventually()(service.lookup(id) shouldBe Some(failed))
              id
            },
            forExactly(1, _)(_.warningMessage should include("Onboarding snapshot export")),
          )
        clock.advance(Duration.ofHours(24).minusSeconds(1))
        service.removeExpired(clock.now).futureValue shouldBe 0
        service.lookup(failedId) shouldBe Some(failed)

        loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
          {
            clock.advance(Duration.ofSeconds(1))
            service.removeExpired(clock.now).futureValue shouldBe 1
            service.removeExpired(clock.now).futureValue shouldBe 0
          },
          entries =>
            forExactly(1, entries)(_.message should startWith("Removed onboarding snapshot file")),
        )
        service.lookup(failedId) shouldBe None
    }

    "keep failed exports across restarts without reusing them" in File
      .usingTemporaryDirectory() { directory =>
        val config = SvOnboardingSnapshotsConfig(directory.path)
        val failedId = new java.util.concurrent.atomic.AtomicReference[SvOnboardingSnapshotId]()
        withService(_ => config) { (service, _, _) =>
          loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
            {
              failedId.set(
                service
                  .prepare(key("sv1"), writeSnapshot(Future.failed(new RuntimeException("boom"))))
                  .futureValue
              )
              eventually()(service.lookup(failedId.get()) should matchPattern {
                case Some(SnapshotState.Failed(_)) =>
              })
            },
            forExactly(1, _)(_.warningMessage should include("failed")),
          )
        }
        withService(_ => config) { (service, _, _) =>
          service.lookup(failedId.get()) shouldBe Some(
            SnapshotState.Failed("Snapshot export failed")
          )
          service
            .prepare(key("sv1"), writeSnapshot(Future.unit))
            .futureValue should not be failedId.get()
        }
      }

    "limit concurrent ACS and sequencer exports and reject a full queue" in withService(
      SvOnboardingSnapshotsConfig(_, parallelism = PositiveInt.two, queueSize = PositiveInt.one)
    ) { (service, _, _) =>
      final class Export(snapshotKey: SnapshotKey) {
        val started = Promise[Unit]()
        val done = Promise[Unit]()
        def prepare(): SvOnboardingSnapshotId = service
          .prepare(
            snapshotKey,
            file => {
              Files.write(file, Array[Byte](1))
              started.success(())
              done.future
            },
          )
          .futureValue
      }
      val slow = new Export(key("sv1"))
      val fast = new Export(sequencerKey("sv2"))
      val queued = new Export(sequencerKey("sv3"))
      try {
        Seq(slow, fast).foreach { running =>
          running.prepare()
          running.started.future.futureValue
        }
        queued.prepare()
        loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(Level.WARN))(
          inside(
            service.prepare(key("sv4"), _ => Future.unit).failed.futureValue
          ) { case e: StatusRuntimeException =>
            e.getStatus.getCode shouldBe Status.Code.UNAVAILABLE
          },
          _.warningMessage should include("Too many onboarding snapshot exports queued"),
        )
        queued.started.isCompleted shouldBe false

        fast.done.success(())
        queued.started.future.futureValue
      } finally Seq(slow, fast, queued).foreach(_.done.trySuccess(()))
    }

    "remove snapshots after the retention period from the cleanup trigger" in withService(
      SvOnboardingSnapshotsConfig(_, retention = PositiveFiniteDuration.ofHours(24))
    ) { (service, clock, retryProvider) =>
      val trigger = new SvOnboardingSnapshotCleanupTrigger(
        TriggerContext(
          AutomationConfig(),
          clock,
          clock,
          TriggerEnabledSynchronization.Noop,
          retryProvider,
          loggerFactory,
          NoOpMetricsFactory,
        ),
        service,
      )
      val ready =
        service.prepare(key("sv1"), writeSnapshot(Future.unit)).futureValue
      val readyFile = eventually() {
        inside(service.lookup(ready)) { case Some(SnapshotState.Ready(file, _)) => file }
      }
      val exported = Promise[Unit]()
      val exporting = service.prepare(key("sv2"), writeSnapshot(exported.future)).futureValue

      try {
        clock.advance(Duration.ofHours(24).minusSeconds(1))
        trigger.performWorkIfAvailable().futureValue
        service.lookup(ready) should matchPattern { case Some(SnapshotState.Ready(_, _)) => }

        clock.advance(Duration.ofSeconds(1))
        trigger.performWorkIfAvailable().futureValue
        service.lookup(ready) shouldBe None
        Files.exists(readyFile) shouldBe false
        eventually()(service.lookup(exporting) should matchPattern {
          case Some(SnapshotState.Exporting(_, _)) =>
        })
        service
          .prepare(key("sv1"), writeSnapshot(Future.unit))
          .futureValue should not be ready

        exported.success(())
        val exportedFile = eventually() {
          inside(service.lookup(exporting)) { case Some(SnapshotState.Ready(file, _)) => file }
        }
        service.removeExpired(clock.now).futureValue shouldBe 0
        service
          .prepare(key("sv2"), _ => fail("must reuse the finished export"))
          .futureValue shouldBe exporting

        clock.advance(Duration.ofHours(24))
        trigger.performWorkIfAvailable().futureValue
        service.lookup(exporting) shouldBe None
        Files.exists(exportedFile) shouldBe false
      } finally {
        exported.trySuccess(())
        trigger.close()
      }
    }

    "keep processing exports when deleting a failed export fails and retry cleanup" in withService() {
      (service, clock, _) =>
        val blocker = Promise[Path]()
        val failedId = loggerFactory.assertEventuallyLogsSeq(
          SuppressionRule.LevelAndAbove(Level.WARN)
        )(
          {
            val id = service
              .prepare(
                key("sv1"),
                file => {
                  blocker.success(preventDeletion(file))
                  Future.failed(new RuntimeException("export failed"))
                },
              )
              .futureValue
            eventually()(
              service.lookup(id) shouldBe Some(SnapshotState.Failed("Snapshot export failed"))
            )
            id
          },
          entries => {
            forExactly(1, entries)(_.warningMessage should include("Onboarding snapshot export"))
            forExactly(1, entries)(_.warningMessage should include("cleanup will retry"))
          },
        )

        val next =
          service.prepare(key("sv2"), writeSnapshot(Future.unit)).futureValue
        eventually()(service.lookup(next) should matchPattern {
          case Some(SnapshotState.Ready(_, _)) =>
        })

        val child = blocker.future.futureValue
        val leftover = child.getParent
        Files.delete(child)
        service.removeExpired(clock.now).futureValue shouldBe 0
        Files.exists(leftover) shouldBe false
        Files.exists(leftover.resolveSibling("failure.json")) shouldBe true
        service.lookup(failedId) shouldBe Some(SnapshotState.Failed("Snapshot export failed"))
        service.lookup(next) should matchPattern { case Some(SnapshotState.Ready(_, _)) => }

        clock.advance(Duration.ofHours(24))
        service.removeExpired(clock.now).futureValue shouldBe 2
        Files.exists(leftover.getParent) shouldBe false
        service.lookup(failedId) shouldBe None
    }

    "keep processing exports when recording a failed export fails" in {
      val clock = new FailingClock
      withService(clock = clock) { (service, _, _) =>
        val exported = Promise[Unit]()
        val failedId = service.prepare(key("sv1"), writeSnapshot(exported.future)).futureValue
        eventually()(service.lookup(failedId) should matchPattern {
          case Some(SnapshotState.Exporting(_, _)) =>
        })
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          {
            clock.failing.set(true)
            exported.failure(new RuntimeException("export failed"))
            eventually()(service.lookup(failedId) shouldBe None)
          },
          entries => {
            forAtLeast(1, entries)(
              _.warningMessage should include(s"Onboarding snapshot export $failedId failed")
            )
            forExactly(1, entries)(_.warningMessage should include("Failed to record the failure"))
          },
        )
        clock.failing.set(false)
        val next = service.prepare(key("sv2"), writeSnapshot(Future.unit)).futureValue
        eventually()(service.lookup(next) should matchPattern {
          case Some(SnapshotState.Ready(_, _)) =>
        })
      }
    }

    "record a snapshot whose file disappeared when cleanup finds it" in withService() {
      (service, clock, _) =>
        val id = service.prepare(key("sv1"), writeSnapshot(Future.unit)).futureValue
        val file = eventually() {
          inside(service.lookup(id)) { case Some(SnapshotState.Ready(file, _)) => file }
        }
        Files.delete(file)
        loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(Level.WARN))(
          service.removeExpired(clock.now).futureValue shouldBe 0,
          _.warningMessage should include(s"Onboarding snapshot $id is unusable"),
        )
        Files.exists(file.resolveSibling("metadata.json")) shouldBe false
        service.lookup(id) shouldBe Some(SnapshotState.Failed("Snapshot file is missing"))
        service.removeExpired(clock.now).futureValue shouldBe 0
        clock.advance(Duration.ofHours(24))
        service.removeExpired(clock.now).futureValue shouldBe 1
        service.lookup(id) shouldBe None
    }

    "retry an expired file deletion without retaining download eligibility or blocking other cleanup" in withService() {
      (service, clock, _) =>
        val first =
          service.prepare(key("sv1"), writeSnapshot(Future.unit)).futureValue
        val second =
          service.prepare(key("sv2"), writeSnapshot(Future.unit)).futureValue
        def fileOf(id: SvOnboardingSnapshotId): Path = eventually() {
          inside(service.lookup(id)) { case Some(SnapshotState.Ready(file, _)) => file }
        }
        val firstFile = fileOf(first)
        val secondFile = fileOf(second)
        val child = preventDeletion(firstFile)
        clock.advance(Duration.ofHours(24))

        loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(Level.WARN))(
          service.removeExpired(clock.now).futureValue shouldBe 1,
          _.warningMessage should include("cleanup will retry"),
        )
        service.lookup(first) shouldBe None
        service.lookup(second) shouldBe None
        Files.exists(firstFile) shouldBe true
        Files.exists(secondFile) shouldBe false

        val replacement =
          service.prepare(key("sv1"), writeSnapshot(Future.unit)).futureValue
        replacement should not be first
        val replacementFile = fileOf(replacement)
        Files.delete(child)
        service.removeExpired(clock.now).futureValue shouldBe 1
        Files.exists(firstFile) shouldBe false
        Files.exists(replacementFile) shouldBe true
        service.removeExpired(clock.now).futureValue shouldBe 0
    }

    Seq(false, true).foreach { closeRetryProviderFirst =>
      s"wait for active writers when closing with retry-provider-first=$closeRetryProviderFirst" in withService() {
        (service, _, retryProvider) =>
          val exported = Promise[Unit]()
          val started = Promise[Path]()
          val queuedStarted = new AtomicInteger()
          val active = service
            .prepare(
              key("sv1"),
              file => {
                val output = Files.newOutputStream(file)
                output.write(1)
                started.success(file)
                exported.future.map { _ =>
                  try output.write(2)
                  finally output.close()
                }
              },
            )
            .futureValue
          val file = started.future.futureValue
          service
            .prepare(
              key("sv2"),
              _ => {
                queuedStarted.incrementAndGet()
                Future.unit
              },
            )
            .futureValue

          if (closeRetryProviderFirst) retryProvider.close()
          val closed = Future(blocking(service.close()))
          try {
            eventually()(service.isClosing shouldBe true)
            closed.isCompleted shouldBe false
            Files.exists(file) shouldBe true
            unavailable(service.lookup(active))
            inside(
              service.prepare(key("sv1"), _ => Future.unit).failed.futureValue
            ) { case e: StatusRuntimeException =>
              e.getStatus.getCode shouldBe Status.Code.UNAVAILABLE
            }
          } finally exported.trySuccess(())

          closed.futureValue
          unavailable(service.lookup(active))
          Files.readAllBytes(file) shouldBe Array[Byte](1, 2)
          Files.exists(file.getParent.resolve("metadata.json")) shouldBe true
          queuedStarted.get() shouldBe 0
      }
    }

    "recover completed snapshots with the same id and original retention after restart" in File
      .usingTemporaryDirectory() { directory =>
        val config = SvOnboardingSnapshotsConfig(directory.path)
        val id = new java.util.concurrent.atomic.AtomicReference[SvOnboardingSnapshotId]()
        withService(_ => config) { (service, clock, _) =>
          clock.advance(Duration.ofHours(1))
          id.set(service.prepare(key("sv1"), writeSnapshot(Future.unit)).futureValue)
          eventually()(service.lookup(id.get()) should matchPattern {
            case Some(SnapshotState.Ready(_, _)) =>
          })
        }
        withService(_ => config) { (service, clock, _) =>
          inside(service.lookup(id.get())) { case Some(SnapshotState.Ready(file, digest)) =>
            File(file).contentAsString shouldBe "snapshot"
            digest shouldBe sha256Base64
          }
          service
            .prepare(key("sv1"), _ => fail("must reuse the persisted export"))
            .futureValue shouldBe id.get()
          clock.advance(Duration.ofHours(24))
          service.removeExpired(clock.now).futureValue shouldBe 0
          service.lookup(id.get()) should matchPattern { case Some(SnapshotState.Ready(_, _)) => }
          clock.advance(Duration.ofHours(1))
          service.removeExpired(clock.now).futureValue shouldBe 1
          service.lookup(id.get()) shouldBe None
          service
            .prepare(key("sv1"), writeSnapshot(Future.unit))
            .futureValue should not be id.get()
        }
      }

    "recover sequencer onboarding snapshots after restart" in File.usingTemporaryDirectory() {
      directory =>
        val config = SvOnboardingSnapshotsConfig(directory.path)
        val snapshotKey = sequencerKey("sequencer")
        val id = new java.util.concurrent.atomic.AtomicReference[SvOnboardingSnapshotId]()
        withService(_ => config) { (service, _, _) =>
          id.set(service.prepare(snapshotKey, writeSnapshot(Future.unit)).futureValue)
          eventually()(service.lookup(id.get()) should matchPattern {
            case Some(SnapshotState.Ready(_, _)) =>
          })
        }
        withService(_ => config) { (service, _, _) =>
          service
            .prepare(snapshotKey, _ => fail("must reuse the persisted export"))
            .futureValue shouldBe id.get()
          service
            .prepare(key("sv1"), writeSnapshot(Future.unit))
            .futureValue should not be id.get()
        }
    }

    "export ACS and sequencer snapshots stored without a valid digest again" in File
      .usingTemporaryDirectory() { directory =>
        withService(_ => SvOnboardingSnapshotsConfig(directory.path)) { (service, clock, _) =>
          val sequencer = sequencerKey("seq1")
          val noDigest = "Snapshot has no SHA-256 digest"
          Seq[(SnapshotKey, String, String)](
            (
              key("sv1"),
              """"party":"sv1::dummy","activationTime":"1970-01-01T00:00:00Z"""",
              noDigest,
            ),
            (
              sequencer,
              s""""party":null,"activationTime":null,"sequencerId":"${sequencer.sequencerId.toProtoPrimitive}"""",
              noDigest,
            ),
            (
              key("sv2"),
              s""""party":"sv2::dummy","activationTime":"1970-01-01T00:00:00Z","sha256Base64":"${Base64.getEncoder
                  .encodeToString("short".getBytes)}"""",
              "Snapshot has an invalid SHA-256 digest",
            ),
            (
              key("sv3"),
              """"party":"sv3::dummy","activationTime":"1970-01-01T00:00:00Z","sha256Base64":"not-base64!"""",
              "Snapshot has an invalid SHA-256 digest",
            ),
          ).foreach { case (snapshotKey, keyFields, reason) =>
            val legacyId = SvOnboardingSnapshotId(UUID.randomUUID().toString)
            val exportDirectory = (directory / "exports" / legacyId.value).createDirectories()
            (exportDirectory / "snapshot").writeText("snapshot")
            (exportDirectory / "metadata.json").writeText(
              s"""{"synchronizerId":"synchronizer::dummy",$keyFields,"createdAt":"${clock.now.toInstant}","size":8}"""
            )
            val id =
              service.prepare(snapshotKey, writeSnapshot(Future.unit)).futureValue
            id should not be legacyId
            loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(Level.WARN))(
              service.lookup(legacyId) shouldBe Some(SnapshotState.Failed(reason)),
              _.warningMessage should include("is unusable"),
            )
            eventually()(service.lookup(id) should matchPattern {
              case Some(SnapshotState.Ready(_, `sha256Base64`)) =>
            })
          }
        }
      }

    "remove interrupted exports without touching other files" in File
      .usingTemporaryDirectory() { directory =>
        val interrupted = directory / "exports" / UUID.randomUUID().toString
        (interrupted / "snapshot").createIfNotExists(createParents = true)
        (interrupted / "metadata.tmp").writeText("unfinished metadata")
        val download =
          (directory / "downloads" / "acs" / "snapshot").createIfNotExists(createParents = true)
        val otherFile = (directory / "other").createIfNotExists()
        withService(_ => SvOnboardingSnapshotsConfig(directory.path)) { (service, clock, _) =>
          service.removeExpired(clock.now).futureValue shouldBe 1
          interrupted.exists shouldBe false
          download.exists shouldBe true
          otherFile.exists shouldBe true
        }
      }

    "not reuse an expired snapshot while waiting for cleanup" in withService() {
      (service, clock, _) =>
        val id = service.prepare(key("sv1"), writeSnapshot(Future.unit)).futureValue
        eventually()(service.lookup(id) should matchPattern {
          case Some(SnapshotState.Ready(_, _)) =>
        })
        clock.advance(Duration.ofHours(24))
        service.lookup(id) shouldBe None
        service
          .prepare(key("sv1"), writeSnapshot(Future.unit))
          .futureValue should not be id
    }

    "remove exports with corrupt completion metadata" in File.usingTemporaryDirectory() {
      directory =>
        val corrupt = directory / "exports" / UUID.randomUUID().toString
        (corrupt / "snapshot").createIfNotExists(createParents = true)
        (corrupt / "metadata.json").writeText("not json")
        withService(_ => SvOnboardingSnapshotsConfig(directory.path)) { (service, clock, _) =>
          loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(Level.WARN))(
            service.removeExpired(clock.now).futureValue shouldBe 1,
            _.warningMessage should include("Cannot recover onboarding snapshot"),
          )
          corrupt.exists shouldBe false
        }
    }

    "never resolve a download id outside the export directory" in withService() { (service, _, _) =>
      service.lookup(SvOnboardingSnapshotId("../../other")) shouldBe None
    }

    "reject exports and lookups after shutdown" in withService() { (service, _, retryProvider) =>
      retryProvider.close()
      inside(
        service.prepare(key("sv1"), _ => Future.unit).failed.futureValue
      ) { case e: StatusRuntimeException =>
        e.getStatus.getCode shouldBe Status.Code.UNAVAILABLE
      }
      unavailable(service.lookup(SvOnboardingSnapshotId(UUID.randomUUID().toString)))
    }
  }
}

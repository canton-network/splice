// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.http

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.parser.decode
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.provide
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.sv_public.SvPublicResource
import org.lfdecentralizedtrust.splice.http.v0.sv_public_stream.{
  SvPublicStreamHandler,
  SvPublicStreamResource,
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotId
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService.{
  SnapshotKey,
  SnapshotState,
}
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.{Future, Promise}

class OnboardSvSnapshotHandlerTest extends AnyWordSpec with BaseTest with ScalatestRouteTest {

  private val retryProvider =
    RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)
  private val snapshotDirectory = better.files.File.newTemporaryDirectory()
  private val snapshotService = new SvOnboardingSnapshotService(
    SvOnboardingSnapshotsConfig(snapshotDirectory.path),
    new SimClock(loggerFactory = loggerFactory),
    retryProvider,
    loggerFactory,
  )

  override def afterAll(): Unit = {
    retryProvider.close()
    snapshotService.close()
    snapshotDirectory.delete()
    super.afterAll()
  }

  private val handler = new SvPublicStreamHandler[TraceContext] {
    private def unsupported[T] = Future.failed[T](new UnsupportedOperationException)
    override def onboardSvSequencer(
        respond: SvPublicStreamResource.OnboardSvSequencerResponse.type
    )(body: definitions.OnboardSvSequencerRequest)(extracted: TraceContext) = unsupported
    override def onboardSvDownload(
        respond: SvPublicStreamResource.OnboardSvDownloadResponse.type
    )(id: String)(extracted: TraceContext) =
      HttpSvPublicHandler.onboardSvDownloadResponse(snapshotService, SvOnboardingSnapshotId(id))
  }

  private val route = Route.seal(
    SvPublicStreamResource.routes(handler, _ => provide(traceContext))
  )

  private val content = "0123456789".getBytes

  private def prepare(name: String, exportTo: Path => Future[Unit]): SvOnboardingSnapshotId =
    snapshotService
      .prepare(
        SnapshotKey.Acs(
          SynchronizerId.tryFromString("synchronizer::dummy"),
          PartyId.tryFromProtoPrimitive(s"$name::dummy"),
          Instant.EPOCH,
        ),
        exportTo,
      )
      .futureValue

  private def writeSnapshot(file: Path): Future[Unit] = {
    Files.write(file, content)
    Future.unit
  }

  private def verifyNothingPrepared(service: SvOnboardingSnapshotService) =
    verify(service, never).prepare(any[SnapshotKey], any[Path => Future[Unit]])(
      any[TraceContext]
    )

  private def download(id: SvOnboardingSnapshotId) =
    Get(s"/api/sv/v0/onboard/sv/download/${id.value}")

  "onboardSvDownload" should {

    "report unknown ids as not found and failed exports as gone" in {
      val failed =
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          {
            val id = prepare(
              "failed",
              _ =>
                Future.failed(new RuntimeException("/snapshots/private/export (Permission denied)")),
            )
            eventually()(
              snapshotService.lookup(id) shouldBe Some(
                SnapshotState.Failed("Snapshot export failed")
              )
            )
            id
          },
          forExactly(1, _)(_.warningMessage should include("failed")),
        )
      download(failed) ~> route ~> check {
        status shouldBe StatusCodes.Gone
        val body = responseAs[String]
        body should include(s"Onboarding snapshot $failed failed: Snapshot export failed")
        body should not include "/snapshots/private"
        body should not include "Permission denied"
      }

      download(SvOnboardingSnapshotId("unknown")) ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[String] should include("Onboarding snapshot unknown not found")
      }
    }

    "report queued and running exports as accepted with their state and times" in {
      def pendingBody = decode[definitions.OnboardSvDownloadPendingResponse](responseAs[String])
        .fold(throw _, identity)
      val exported = Promise[Unit]()
      try {
        val exporting = prepare(
          "exporting",
          file => {
            Files.write(file, content)
            exported.future
          },
        )
        eventually()(snapshotService.lookup(exporting) should matchPattern {
          case Some(SnapshotState.Exporting(_, _)) =>
        })
        val queued = prepare("queued", writeSnapshot)
        download(exporting) ~> route ~> check {
          status shouldBe StatusCodes.Accepted
          pendingBody.status shouldBe definitions.OnboardSvDownloadPendingResponse.Status.Exporting
          pendingBody.startedAt shouldBe defined
        }
        download(queued) ~> route ~> check {
          status shouldBe StatusCodes.Accepted
          pendingBody.status shouldBe definitions.OnboardSvDownloadPendingResponse.Status.Queued
          pendingBody.startedAt shouldBe None
        }
      } finally exported.trySuccess(()).discard
    }

    "report a completed snapshot whose file is missing as gone and record it once" in {
      val id = prepare("missing-file", writeSnapshot)
      val file = eventually() {
        inside(snapshotService.lookup(id)) { case Some(SnapshotState.Ready(file)) => file }
      }
      Files.delete(file)
      loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(Level.WARN))(
        download(id) ~> route ~> check {
          status shouldBe StatusCodes.Gone
          val body = responseAs[String]
          body should include(s"Onboarding snapshot $id failed: Snapshot file is missing")
          body should not include file.toString
        },
        _.warningMessage should include("is unusable"),
      )
      Files.exists(file.resolveSibling("metadata.json")) shouldBe false
      download(id) ~> route ~> check {
        status shouldBe StatusCodes.Gone
      }
    }
  }

  "onboardSvPartyMigrationPrepare" should {

    "return the proposal serial without exporting when the proposal was not found" in {
      val service = mock[SvOnboardingSnapshotService]
      HttpSvPublicHandler
        .onboardSvPartyMigrationPrepareResponse(
          service,
          Left(DsoPartyHosting.RequiredProposalNotFound(PositiveInt.tryCreate(3))),
          (_, file) => writeSnapshot(file),
        )
        .futureValue shouldBe SvPublicResource.OnboardSvPartyMigrationPrepareResponse
        .BadRequest(
          definitions.ProposalNotFoundErrorResponse(
            definitions.ProposalNotFoundErrorResponse.ProposalNotFound(BigInt(3))
          )
        )
      verifyNothingPrepared(service)
    }
  }

  "unavailableOnGrpcFailure" should {

    "turn gRPC failures of a readiness check into 503 without exposing internal details" in {
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
        HttpSvPublicHandler
          .unavailableOnGrpcFailure("check", logger)(
            Future.failed(
              io.grpc.Status.UNIMPLEMENTED
                .withDescription("Readiness check failed at internal-sequencer:5009")
                .withCause(new RuntimeException("Internal readiness failure"))
                .asRuntimeException()
            )
          )
          .failed
          .futureValue should matchPattern {
          case HttpErrorWithHttpCode(
                StatusCodes.ServiceUnavailable,
                "check failed: UNIMPLEMENTED",
              ) =>
        },
        entries =>
          forExactly(1, entries) { entry =>
            entry.message should include("check failed")
            inside(entry.throwable) { case Some(e) =>
              e.getMessage should include("internal-sequencer:5009")
            }
          },
      )
    }
  }
}

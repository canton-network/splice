// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.joining

import better.files.File
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpRequest,
  StatusCode,
  StatusCodes,
}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.environment.{RetryFor, RetryProvider}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvStreamClient.{
  OnboardSvDownloadResponse
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotId
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

import java.io.IOException
import java.net.ConnectException
import java.nio.file.{Files, Path}
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}

class SvOnboardingSnapshotDownloadTest
    extends AnyWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext {

  private val content = "0123456789".getBytes

  private def full() =
    OnboardSvDownloadResponse.OK(HttpEntity(ContentTypes.`application/octet-stream`, content))

  private class Interrupted(bytes: Int) {
    private val cut = Promise[ByteString]()
    private val data = Source.single(ByteString(content.take(bytes))) ++ Source.future(cut.future)
    val response = OnboardSvDownloadResponse.OK(
      HttpEntity.Default(ContentTypes.`application/octet-stream`, content.length.toLong, data)
    )
    def after(snapshotDownload: SvOnboardingSnapshotDownload, result: Future[Path]) = {
      val file = snapshotDownload.directory.resolve("snapshot")
      eventually() {
        Files.exists(file) shouldBe true
        Files.size(file) shouldBe bytes.toLong
      }
      cut.failure(new IOException("cut"))
      failsUnavailable(result)
    }
  }

  private class Sponsor(responses: OnboardSvDownloadResponse*) {
    private val remaining = mutable.Queue(responses*)
    val requests = mutable.ListBuffer.empty[String]
    def download(id: SvOnboardingSnapshotId): Future[OnboardSvDownloadResponse] = {
      requests += id.value
      Future.successful(remaining.dequeue())
    }
  }

  private class UnreliableSponsor(responses: Either[Throwable, OnboardSvDownloadResponse]*) {
    private val remaining = mutable.Queue(responses*)
    val requests = mutable.ListBuffer.empty[String]
    val download: SvOnboardingSnapshotId => Future[OnboardSvDownloadResponse] = { id =>
      requests += id.value
      remaining.dequeue().fold(Future.failed, Future.successful)
    }
  }

  private def pendingResponse(status: definitions.OnboardSvDownloadPendingResponse.Status) =
    OnboardSvDownloadResponse.Accepted(
      definitions.OnboardSvDownloadPendingResponse(
        status,
        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
      )
    )

  private val retryProvider =
    RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)
  private val quickly = RetryFor(10, 1.millis, 1.millis, resetRetriesAfter = None)

  override def afterAll(): Unit = {
    retryProvider.close()
    super.afterAll()
  }

  private def downloadWithRetries(
      snapshotDownload: SvOnboardingSnapshotDownload,
      id: String,
      sponsor: UnreliableSponsor,
  ) =
    snapshotDownload.downloadWithRetries(
      SvOnboardingSnapshotId(id),
      sponsor.download,
      retryProvider,
      quickly,
      logger,
    )

  private def httpError(status: StatusCode) = HttpCommandException(
    HttpRequest(),
    status,
    HttpCommandException.ErrorResponseBody(definitions.ErrorResponse("snapshot response")),
  )

  private def withDownload(test: SvOnboardingSnapshotDownload => Unit): Unit =
    File.usingTemporaryDirectory() { directory =>
      test(
        SvOnboardingSnapshotDownload(
          SvOnboardingSnapshotsConfig(directory.path),
          "test",
        )
      )
    }

  private def failsUnavailable(result: Future[Path]) =
    inside(result.failed.futureValue) { case e: StatusRuntimeException =>
      e.getStatus.getCode shouldBe Status.Code.UNAVAILABLE
    }

  "SvOnboardingSnapshotDownload" should {

    "fail with a non-retryable error only when the sponsor no longer has the snapshot" in withDownload {
      snapshotDownload =>
        val id = SvOnboardingSnapshotId("id")
        Seq[StatusCode](StatusCodes.NotFound, StatusCodes.Gone).foreach { status =>
          val error = httpError(status)
          snapshotDownload
            .download(id, new UnreliableSponsor(Left(error)).download)
            .failed
            .futureValue shouldBe SvOnboardingSnapshotDownload.PrepareAgain(error)
        }
        Seq[StatusCode](StatusCodes.InternalServerError, StatusCodes.ServiceUnavailable).foreach {
          status =>
            val error = httpError(status)
            snapshotDownload
              .download(id, new UnreliableSponsor(Left(error)).download)
              .failed
              .futureValue shouldBe error
        }
        failsUnavailable(
          snapshotDownload.download(
            id,
            new UnreliableSponsor(
              Right(pendingResponse(definitions.OnboardSvDownloadPendingResponse.Status.Queued))
            ).download,
          )
        )
    }

    "retry the same id while the export is pending or the sponsor or network fails" in withDownload {
      snapshotDownload =>
        val sponsor = new UnreliableSponsor(
          Right(pendingResponse(definitions.OnboardSvDownloadPendingResponse.Status.Queued)),
          Left(httpError(StatusCodes.InternalServerError)),
          Left(httpError(StatusCodes.ServiceUnavailable)),
          Left(new ConnectException("connection refused")),
          Right(pendingResponse(definitions.OnboardSvDownloadPendingResponse.Status.Exporting)),
          Right(full()),
        )
        Files.readAllBytes(
          downloadWithRetries(snapshotDownload, "id", sponsor).futureValue
        ) shouldBe content
        sponsor.requests shouldBe Seq.fill(6)("id")
    }

    "ask to prepare again without retrying the download when the sponsor answers 404 or 410" in withDownload {
      snapshotDownload =>
        Seq[StatusCode](StatusCodes.NotFound, StatusCodes.Gone).foreach { status =>
          val sponsor = new UnreliableSponsor(Left(httpError(status)), Right(full()))
          inside(downloadWithRetries(snapshotDownload, "id", sponsor).failed.futureValue) {
            case e: StatusRuntimeException =>
              e.getStatus.getCode shouldBe Status.Code.FAILED_PRECONDITION
          }
          sponsor.requests shouldBe Seq("id")
        }
    }

    "surface other client errors without retrying the download" in withDownload {
      snapshotDownload =>
        val error = httpError(StatusCodes.Unauthorized)
        val sponsor = new UnreliableSponsor(Left(error), Right(full()))
        loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(Level.WARN))(
          downloadWithRetries(snapshotDownload, "id", sponsor).failed.futureValue shouldBe error,
          _.warningMessage should include("non-retryable HTTP error"),
        )
        sponsor.requests shouldBe Seq("id")
    }

    "prepare again with a new id when the sponsor no longer has the snapshot" in withDownload {
      snapshotDownload =>
        val prepares = new AtomicInteger()
        val sponsor = new UnreliableSponsor(Left(httpError(StatusCodes.Gone)), Right(full()))
        val file = retryProvider
          .retry(
            quickly,
            "prepare_and_download",
            "prepare and download the snapshot",
            downloadWithRetries(snapshotDownload, s"id${prepares.incrementAndGet()}", sponsor),
            logger,
          )
          .futureValue
        Files.readAllBytes(file) shouldBe content
        prepares.get() shouldBe 2
        sponsor.requests shouldBe Seq("id1", "id2")
    }

    "download again from the start after an interrupted download" in withDownload {
      snapshotDownload =>
        val id = SvOnboardingSnapshotId("id")
        val interrupted = new Interrupted(4)
        val sponsor = new Sponsor(interrupted.response, full())
        interrupted.after(snapshotDownload, snapshotDownload.download(id, sponsor.download))
        Files.readAllBytes(
          snapshotDownload.download(id, sponsor.download).futureValue
        ) shouldBe content
        sponsor.requests shouldBe Seq("id", "id")
    }

    "download the whole snapshot" in withDownload { snapshotDownload =>
      val sponsor = new Sponsor(full())
      val file =
        snapshotDownload.download(SvOnboardingSnapshotId("id"), sponsor.download).futureValue
      Files.readAllBytes(file) shouldBe content
      sponsor.requests shouldBe Seq("id")
    }

    "recreate the download directory if cleanup removes it while waiting" in withDownload {
      snapshotDownload =>
        val id = SvOnboardingSnapshotId("id")
        val unavailable = httpError(StatusCodes.ServiceUnavailable)
        val sponsor = new UnreliableSponsor(Left(unavailable), Right(full()))
        snapshotDownload.download(id, sponsor.download).failed.futureValue shouldBe unavailable
        File(snapshotDownload.directory).delete()
        Files.readAllBytes(
          snapshotDownload.download(id, sponsor.download).futureValue
        ) shouldBe content
    }

    "never write outside its directory" in File.usingTemporaryDirectory() { directory =>
      val snapshotDownload = SvOnboardingSnapshotDownload(
        SvOnboardingSnapshotsConfig(directory.path),
        "test",
      )
      val file =
        snapshotDownload
          .download(SvOnboardingSnapshotId("../../unrelated"), new Sponsor(full()).download)
          .futureValue
      file.getParent shouldBe snapshotDownload.directory
      directory.listRecursively.map(_.name).toSeq should not contain "unrelated"
    }

    "preserve an existing directory below the configured one" in File.usingTemporaryDirectory() {
      directory =>
        val stale = (directory / "downloads" / "acs" / "stale").createIfNotExists(
          createParents = true
        )
        val exports = (directory / "exports").createDirectories()
        val snapshotDownload = SvOnboardingSnapshotDownload(
          SvOnboardingSnapshotsConfig(directory.path),
          "acs",
        )
        snapshotDownload.directory shouldBe (directory / "downloads" / "acs").path
        stale.exists shouldBe true
        exports.exists shouldBe true
        snapshotDownload.delete()
        Files.exists(snapshotDownload.directory) shouldBe false
    }

    "reuse a complete download after restart only for the same id" in File
      .usingTemporaryDirectory() { directory =>
        val config = SvOnboardingSnapshotsConfig(directory.path)
        def downloader = SvOnboardingSnapshotDownload(config, "acs")
        val id = SvOnboardingSnapshotId("id")
        val first = downloader.download(id, new Sponsor(full()).download).futureValue
        val reused = downloader
          .download(id, _ => fail("must not download the completed snapshot again"))
          .futureValue
        reused shouldBe first
        Files.readAllBytes(reused) shouldBe content
        val changed = new Sponsor(full())
        downloader.download(SvOnboardingSnapshotId("new-id"), changed.download).futureValue
        changed.requests shouldBe Seq("new-id")
      }

    "redownload an incomplete or truncated snapshot after restart" in File
      .usingTemporaryDirectory() { directory =>
        val config = SvOnboardingSnapshotsConfig(directory.path)
        def downloader = SvOnboardingSnapshotDownload(config, "acs")
        val id = SvOnboardingSnapshotId("id")
        val interrupted = new Interrupted(4)
        val first = downloader
        interrupted.after(first, first.download(id, new Sponsor(interrupted.response).download))
        val complete = downloader.download(id, new Sponsor(full()).download).futureValue
        Files.readAllBytes(complete) shouldBe content
        Files.write(complete, Array[Byte](1))
        val sponsor = new Sponsor(full())
        Files.readAllBytes(downloader.download(id, sponsor.download).futureValue) shouldBe content
        sponsor.requests shouldBe Seq("id")
      }

    "redownload when completion metadata is corrupt or expired" in File.usingTemporaryDirectory() {
      directory =>
        val config = SvOnboardingSnapshotsConfig(directory.path)
        def downloader = SvOnboardingSnapshotDownload(config, "acs")
        val id = SvOnboardingSnapshotId("id")
        val first = downloader
        first.download(id, new Sponsor(full()).download).futureValue
        val metadata = first.directory.resolve("metadata.json")
        Seq("not json", """{"id":"id","createdAt":"1970-01-01T00:00:00Z","size":10}""").foreach {
          invalid =>
            Files.writeString(metadata, invalid)
            val sponsor = new Sponsor(full())
            Files.readAllBytes(
              downloader.download(id, sponsor.download).futureValue
            ) shouldBe content
            sponsor.requests shouldBe Seq("id")
        }
    }

    "not reuse a response that ended before its declared length" in withDownload {
      snapshotDownload =>
        val short = OnboardSvDownloadResponse.OK(
          HttpEntity.Default(
            ContentTypes.`application/octet-stream`,
            content.length.toLong,
            Source.single(ByteString(content.take(4))),
          )
        )
        val id = SvOnboardingSnapshotId("id")
        failsUnavailable(snapshotDownload.download(id, new Sponsor(short).download))
        val sponsor = new Sponsor(full())
        Files.readAllBytes(
          snapshotDownload.download(id, sponsor.download).futureValue
        ) shouldBe content
        sponsor.requests shouldBe Seq("id")
    }
  }
}

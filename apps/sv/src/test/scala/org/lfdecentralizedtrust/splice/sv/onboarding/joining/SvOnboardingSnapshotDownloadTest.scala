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
  ContentRange,
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
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.Instant
import java.util.Base64
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

  private def reprDigestOf(bytes: Array[Byte]) =
    Some(
      s"sha-256=:${Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))}:"
    )

  private val digest = reprDigestOf(content)

  private def full() =
    OnboardSvDownloadResponse.OK(
      HttpEntity(ContentTypes.`application/octet-stream`, content),
      digest,
    )

  private def partial(
      from: Int,
      range: Option[ContentRange] = None,
      reprDigest: Option[String] = digest,
  ) =
    OnboardSvDownloadResponse.PartialContent(
      HttpEntity(ContentTypes.`application/octet-stream`, content.drop(from)),
      range.orElse(Some(ContentRange(from.toLong, content.length - 1L, content.length.toLong))),
      reprDigest,
    )

  private class Interrupted(bytes: Int) {
    private val cut = Promise[ByteString]()
    private val data = Source.single(ByteString(content.take(bytes))) ++ Source.future(cut.future)
    val response = OnboardSvDownloadResponse.OK(
      HttpEntity.Default(ContentTypes.`application/octet-stream`, content.length.toLong, data),
      digest,
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

  private class StreamedBody(chunks: Int = 100) {
    val pulled = new AtomicInteger()
    private val ended = Promise[Unit]()
    val entity = HttpEntity.Default(
      ContentTypes.`application/octet-stream`,
      chunks.toLong * content.length,
      Source(1 to chunks)
        .map { _ =>
          pulled.incrementAndGet()
          ByteString(content)
        }
        .watchTermination() { (mat, done) =>
          done.onComplete(_ => ended.trySuccess(()))
          mat
        },
    )
    def cancelledEarly() = {
      ended.future.futureValue
      pulled.get() should be < chunks
    }
  }

  private class Sponsor(responses: OnboardSvDownloadResponse*) {
    private val remaining = mutable.Queue(responses*)
    val requests = mutable.ListBuffer.empty[(String, Long)]
    def download(id: SvOnboardingSnapshotId, offset: Long): Future[OnboardSvDownloadResponse] = {
      requests += id.value -> offset
      Future.successful(remaining.dequeue())
    }
  }

  private class UnreliableSponsor(responses: Either[Throwable, OnboardSvDownloadResponse]*) {
    private val remaining = mutable.Queue(responses*)
    val requests = mutable.ListBuffer.empty[(String, Long)]
    val download: (SvOnboardingSnapshotId, Long) => Future[OnboardSvDownloadResponse] = {
      (id, offset) =>
        requests += id.value -> offset
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
        sponsor.requests shouldBe Seq.fill(6)("id" -> 0L)
    }

    "ask to prepare again without retrying the download when the sponsor answers 404 or 410" in withDownload {
      snapshotDownload =>
        Seq[StatusCode](StatusCodes.NotFound, StatusCodes.Gone).foreach { status =>
          val sponsor = new UnreliableSponsor(Left(httpError(status)), Right(full()))
          inside(downloadWithRetries(snapshotDownload, "id", sponsor).failed.futureValue) {
            case e: StatusRuntimeException =>
              e.getStatus.getCode shouldBe Status.Code.FAILED_PRECONDITION
          }
          sponsor.requests shouldBe Seq("id" -> 0L)
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
        sponsor.requests shouldBe Seq("id" -> 0L)
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
        sponsor.requests shouldBe Seq("id1" -> 0L, "id2" -> 0L)
    }

    "resume an interrupted download from where it stopped with the same id" in withDownload {
      snapshotDownload =>
        val id = SvOnboardingSnapshotId("id")
        val interrupted = new Interrupted(4)
        val sponsor = new Sponsor(interrupted.response, partial(4))
        interrupted.after(snapshotDownload, snapshotDownload.download(id, sponsor.download))
        Files.readAllBytes(
          snapshotDownload.download(id, sponsor.download).futureValue
        ) shouldBe content
        sponsor.requests shouldBe Seq("id" -> 0L, "id" -> 4L)
    }

    "accept a full response to a resumed download" in withDownload { snapshotDownload =>
      val id = SvOnboardingSnapshotId("id")
      val interrupted = new Interrupted(4)
      val sponsor = new Sponsor(interrupted.response, full())
      interrupted.after(snapshotDownload, snapshotDownload.download(id, sponsor.download))
      Files.readAllBytes(
        snapshotDownload.download(id, sponsor.download).futureValue
      ) shouldBe content
      sponsor.requests shouldBe Seq("id" -> 0L, "id" -> 4L)
    }

    "restart from zero with the same id when a partial response does not continue the download" in {
      val otherDigest = reprDigestOf("other".getBytes)
      Seq(
        partial(4, range = Some(ContentRange(5L, content.length - 1L, content.length.toLong))),
        partial(4, range = Some(ContentRange(4L, content.length - 1L, content.length + 1L))),
        partial(4, reprDigest = otherDigest),
        OnboardSvDownloadResponse.PartialContent(
          HttpEntity(ContentTypes.`application/octet-stream`, content.drop(4)),
          None,
          digest,
        ),
      ).foreach { invalid =>
        withDownload { snapshotDownload =>
          val id = SvOnboardingSnapshotId("id")
          val interrupted = new Interrupted(4)
          val sponsor = new Sponsor(interrupted.response, invalid, full())
          interrupted.after(snapshotDownload, snapshotDownload.download(id, sponsor.download))
          failsUnavailable(snapshotDownload.download(id, sponsor.download))
          Files.exists(snapshotDownload.directory.resolve("snapshot")) shouldBe false
          Files.readAllBytes(
            snapshotDownload.download(id, sponsor.download).futureValue
          ) shouldBe content
          sponsor.requests shouldBe Seq("id" -> 0L, "id" -> 4L, "id" -> 0L)
        }
      }
    }

    "treat a range past the end as complete only after verifying the whole local file" in {
      withDownload { snapshotDownload =>
        val id = SvOnboardingSnapshotId("id")
        val interrupted = new Interrupted(content.length)
        val sponsor =
          new Sponsor(interrupted.response, OnboardSvDownloadResponse.RangeNotSatisfiable)
        interrupted.after(snapshotDownload, snapshotDownload.download(id, sponsor.download))
        Files.readAllBytes(
          snapshotDownload.download(id, sponsor.download).futureValue
        ) shouldBe content
        sponsor.requests shouldBe Seq("id" -> 0L, "id" -> content.length.toLong)
      }
      withDownload { snapshotDownload =>
        val id = SvOnboardingSnapshotId("id")
        val interrupted = new Interrupted(content.length)
        val sponsor =
          new Sponsor(interrupted.response, OnboardSvDownloadResponse.RangeNotSatisfiable, full())
        interrupted.after(snapshotDownload, snapshotDownload.download(id, sponsor.download))
        val file = snapshotDownload.directory.resolve("snapshot")
        Files.write(file, "9876543210".getBytes)
        failsUnavailable(snapshotDownload.download(id, sponsor.download))
        Files.exists(file) shouldBe false
        Files.readAllBytes(
          snapshotDownload.download(id, sponsor.download).futureValue
        ) shouldBe content
        sponsor.requests shouldBe Seq("id" -> 0L, "id" -> content.length.toLong, "id" -> 0L)
      }
    }

    "cancel the response bodies it rejects" in {
      Seq(None, Some("sha-256=:not-a-digest:")).foreach { digest =>
        withDownload { snapshotDownload =>
          val body = new StreamedBody()
          snapshotDownload
            .download(
              SvOnboardingSnapshotId("id"),
              new Sponsor(OnboardSvDownloadResponse.OK(body.entity, digest)).download,
            )
            .failed
            .futureValue shouldBe a[RetryProvider.QuietNonRetryableException]
          body.cancelledEarly()
        }
      }
      withDownload { snapshotDownload =>
        val id = SvOnboardingSnapshotId("id")
        val interrupted = new Interrupted(4)
        val body = new StreamedBody()
        val notContinuing = OnboardSvDownloadResponse.PartialContent(
          body.entity,
          Some(ContentRange(5L, content.length - 1L, content.length.toLong)),
          digest,
        )
        val sponsor = new Sponsor(interrupted.response, notContinuing)
        interrupted.after(snapshotDownload, snapshotDownload.download(id, sponsor.download))
        failsUnavailable(snapshotDownload.download(id, sponsor.download))
        body.cancelledEarly()
      }
    }

    "cancel the response body when its download metadata cannot be written" in {
      Seq(false, true).foreach { resume =>
        withDownload { snapshotDownload =>
          val id = SvOnboardingSnapshotId("id")
          if (resume) {
            val interrupted = new Interrupted(4)
            interrupted.after(
              snapshotDownload,
              snapshotDownload.download(id, new Sponsor(interrupted.response).download),
            )
          }
          val body = new StreamedBody()
          val response: OnboardSvDownloadResponse =
            if (resume)
              OnboardSvDownloadResponse.PartialContent(
                body.entity,
                Some(ContentRange(4L, content.length - 1L, content.length.toLong)),
                digest,
              )
            else OnboardSvDownloadResponse.OK(body.entity, digest)
          val failure = snapshotDownload
            .download(
              id,
              (_, _) => {
                Files.createDirectories(snapshotDownload.directory.resolve("metadata.tmp"))
                Future.successful(response)
              },
            )
            .failed
            .futureValue
          failure shouldBe an[IOException]
          body.cancelledEarly()
        }
      }
    }

    "discard a full download whose bytes do not match its digest" in withDownload {
      snapshotDownload =>
        val id = SvOnboardingSnapshotId("id")
        val corrupt = OnboardSvDownloadResponse.OK(
          HttpEntity(ContentTypes.`application/octet-stream`, content),
          reprDigestOf("other".getBytes),
        )
        val sponsor = new Sponsor(corrupt, full())
        failsUnavailable(snapshotDownload.download(id, sponsor.download))
        Files.exists(snapshotDownload.directory.resolve("snapshot")) shouldBe false
        Files.readAllBytes(
          snapshotDownload.download(id, sponsor.download).futureValue
        ) shouldBe content
        sponsor.requests shouldBe Seq("id" -> 0L, "id" -> 0L)
    }

    "accept an unpadded digest and a digest listed with other algorithms" in withDownload {
      snapshotDownload =>
        def base64(algorithm: String) =
          Base64.getEncoder.encodeToString(MessageDigest.getInstance(algorithm).digest(content))
        Seq(
          s"sha-256=:${base64("SHA-256").stripSuffix("=")}:",
          s"sha-512=:${base64("SHA-512")}:, sha-256=:${base64("SHA-256")}:",
        ).zipWithIndex.foreach { case (header, index) =>
          val response = OnboardSvDownloadResponse.OK(
            HttpEntity(ContentTypes.`application/octet-stream`, content),
            Some(header),
          )
          Files.readAllBytes(
            snapshotDownload
              .download(SvOnboardingSnapshotId(s"id$index"), new Sponsor(response).download)
              .futureValue
          ) shouldBe content
        }
    }

    "fail without retrying when the digest is missing or invalid" in withDownload {
      snapshotDownload =>
        Seq(
          None -> "is missing the required Repr-Digest header",
          Some("sha-256=:not-a-digest:") -> "has an invalid SHA-256 Repr-Digest header",
        ).foreach { case (header, reason) =>
          val sponsor = new UnreliableSponsor(
            Right(
              OnboardSvDownloadResponse.OK(
                HttpEntity(ContentTypes.`application/octet-stream`, content),
                header,
              )
            ),
            Right(full()),
          )
          downloadWithRetries(
            snapshotDownload,
            "id",
            sponsor,
          ).failed.futureValue.getMessage should include(
            reason
          )
          sponsor.requests shouldBe Seq("id" -> 0L)
        }
    }

    "download the whole snapshot" in withDownload { snapshotDownload =>
      val sponsor = new Sponsor(full())
      val file =
        snapshotDownload.download(SvOnboardingSnapshotId("id"), sponsor.download).futureValue
      Files.readAllBytes(file) shouldBe content
      sponsor.requests shouldBe Seq("id" -> 0L)
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
          .download(id, (_, _) => fail("must not download the completed snapshot again"))
          .futureValue
        reused shouldBe first
        Files.readAllBytes(reused) shouldBe content
        val changed = new Sponsor(full())
        downloader.download(SvOnboardingSnapshotId("new-id"), changed.download).futureValue
        changed.requests shouldBe Seq("new-id" -> 0L)
      }

    "resume an interrupted download after restart and redownload a changed complete file" in File
      .usingTemporaryDirectory() { directory =>
        val config = SvOnboardingSnapshotsConfig(directory.path)
        def downloader = SvOnboardingSnapshotDownload(config, "acs")
        val id = SvOnboardingSnapshotId("id")
        val interrupted = new Interrupted(4)
        val first = downloader
        interrupted.after(first, first.download(id, new Sponsor(interrupted.response).download))
        val resumed = new Sponsor(partial(4))
        val complete = downloader.download(id, resumed.download).futureValue
        Files.readAllBytes(complete) shouldBe content
        resumed.requests shouldBe Seq("id" -> 4L)
        Files.write(complete, Array[Byte](1))
        val sponsor = new Sponsor(full())
        Files.readAllBytes(downloader.download(id, sponsor.download).futureValue) shouldBe content
        sponsor.requests shouldBe Seq("id" -> 0L)
      }

    "redownload when completion metadata is corrupt or expired" in File.usingTemporaryDirectory() {
      directory =>
        val config = SvOnboardingSnapshotsConfig(directory.path)
        def downloader = SvOnboardingSnapshotDownload(config, "acs")
        val id = SvOnboardingSnapshotId("id")
        val first = downloader
        first.download(id, new Sponsor(full()).download).futureValue
        val metadata = first.directory.resolve("metadata.json")
        Seq(
          "not json",
          """{"id":"id","createdAt":"1970-01-01T00:00:00Z","size":10}""",
          s"""{"id":"id","createdAt":"${Instant.now()}","sha256Base64":"not-base64!","length":10}""",
        ).foreach { invalid =>
          Files.writeString(metadata, invalid)
          val sponsor = new Sponsor(full())
          Files.readAllBytes(
            downloader.download(id, sponsor.download).futureValue
          ) shouldBe content
          sponsor.requests shouldBe Seq("id" -> 0L)
        }
    }

    "resume a response that ended before its declared length" in withDownload { snapshotDownload =>
      val short = OnboardSvDownloadResponse.OK(
        HttpEntity.Default(
          ContentTypes.`application/octet-stream`,
          content.length.toLong,
          Source.single(ByteString(content.take(4))),
        ),
        digest,
      )
      val id = SvOnboardingSnapshotId("id")
      failsUnavailable(snapshotDownload.download(id, new Sponsor(short).download))
      val sponsor = new Sponsor(partial(4))
      Files.readAllBytes(
        snapshotDownload.download(id, sponsor.download).futureValue
      ) shouldBe content
      sponsor.requests shouldBe Seq("id" -> 4L)
    }
  }
}

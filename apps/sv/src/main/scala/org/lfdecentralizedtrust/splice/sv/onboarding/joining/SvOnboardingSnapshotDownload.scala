// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.joining

import better.files.File
import com.digitalasset.canton.discard.Implicits.DiscardOps
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.decode
import io.circe.syntax.*
import io.grpc.Status
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.FileIO
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.environment.{RetryFor, RetryProvider}
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvStreamClient.{
  OnboardSvDownloadResponse
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotId

import java.nio.file.{Files, NoSuchFileException, Path, StandardCopyOption}
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.Try
import scala.util.control.NonFatal

final class SvOnboardingSnapshotDownload private (
    val directory: Path,
    config: SvOnboardingSnapshotsConfig,
) {

  import SvOnboardingSnapshotDownload.*

  private val file = directory.resolve("snapshot")
  private val metadataFile = directory.resolve("metadata.json")

  private def writeMetadata(metadata: DownloadMetadata): Unit = {
    Files.createDirectories(directory)
    val temporary = directory.resolve("metadata.tmp")
    Files.writeString(temporary, metadata.asJson.noSpaces)
    Files.move(
      temporary,
      metadataFile,
      StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING,
    )
    ()
  }

  private def completedDownload(id: SvOnboardingSnapshotId): Boolean =
    Try {
      val metadata =
        decode[DownloadMetadata](Files.readString(metadataFile)).fold(throw _, identity)
      metadata.id == id.value && Files.isRegularFile(file) && Files.size(file) == metadata.size &&
      Instant.parse(metadata.createdAt).plus(config.retention.asJava).isAfter(Instant.now())
    }.getOrElse(false)

  def download(
      id: SvOnboardingSnapshotId,
      downloadFrom: SvOnboardingSnapshotId => Future[OnboardSvDownloadResponse],
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Path] =
    Future(blocking(completedDownload(id))).flatMap {
      case true => Future.successful(file)
      case false =>
        val createdAt = Instant.now().toString
        Future(blocking {
          Files.createDirectories(directory)
          Files.deleteIfExists(metadataFile)
        }).flatMap { _ =>
          downloadFrom(id)
            .recoverWith {
              case error @ HttpCommandException(_, StatusCodes.NotFound | StatusCodes.Gone, _) =>
                Future.failed(PrepareAgain(error))
            }
            .flatMap {
              case OnboardSvDownloadResponse.Accepted(pending) =>
                Future.failed(
                  Status.UNAVAILABLE
                    .withDescription(
                      s"Onboarding snapshot $id is ${pending.status}, queued at ${pending.queuedAt}" +
                        pending.startedAt.fold("")(startedAt => s", started at $startedAt")
                    )
                    .asRuntimeException()
                )
              case OnboardSvDownloadResponse.OK(entity) =>
                entity.dataBytes
                  .runWith(FileIO.toPath(file))
                  .flatMap { result =>
                    Future(blocking {
                      if (entity.contentLengthOption.exists(_ != result.count))
                        throw new java.io.IOException("Incomplete onboarding snapshot download")
                      writeMetadata(DownloadMetadata(id.value, createdAt, result.count))
                      file
                    })
                  }
                  .recoverWith { case NonFatal(e) =>
                    Future.failed(
                      Status.UNAVAILABLE
                        .withDescription(s"Download of onboarding snapshot was interrupted: $e")
                        .withCause(e)
                        .asRuntimeException()
                    )
                  }
            }
        }
    }

  def downloadWithRetries(
      id: SvOnboardingSnapshotId,
      downloadFrom: SvOnboardingSnapshotId => Future[OnboardSvDownloadResponse],
      retryProvider: RetryProvider,
      retryFor: RetryFor,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, mat: Materializer, tc: TraceContext): Future[Path] =
    retryProvider
      .retry(
        retryFor,
        "download_onboarding_snapshot",
        s"download onboarding snapshot $id from sponsor",
        download(id, downloadFrom),
        logger,
      )
      .recoverWith { case PrepareAgain(cause) =>
        Future.failed(
          Status.FAILED_PRECONDITION
            .withDescription(
              s"Sponsor answered ${cause.status} for onboarding snapshot $id, preparing a new one"
            )
            .asRuntimeException()
        )
      }

  def delete(): Unit =
    File(directory).delete(swallowIOExceptions = true).discard
}

object SvOnboardingSnapshotDownload {

  private final case class DownloadMetadata(id: String, createdAt: String, size: Long)
  private implicit val encoder: Encoder[DownloadMetadata] = deriveEncoder
  private implicit val decoder: Decoder[DownloadMetadata] = deriveDecoder

  final case class PrepareAgain(cause: HttpCommandException)
      extends RetryProvider.QuietNonRetryableException(cause.getMessage)

  def downloadDirectory(config: SvOnboardingSnapshotsConfig, name: String): Path =
    config.directory.resolve("downloads").resolve(name)

  def deleteDownload(directory: Path): Unit =
    try File(directory).delete().discard
    catch { case _: NoSuchFileException => () }

  def apply(
      config: SvOnboardingSnapshotsConfig,
      name: String,
  ): SvOnboardingSnapshotDownload = {
    new SvOnboardingSnapshotDownload(
      File(downloadDirectory(config, name)).createDirectories().path,
      config,
    )
  }
}

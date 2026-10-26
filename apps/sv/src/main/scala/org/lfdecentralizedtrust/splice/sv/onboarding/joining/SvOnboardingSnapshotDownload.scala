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
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvStreamClient.{
  OnboardSvDownloadResponse
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.SvOnboardingSnapshotId

import java.nio.file.{Files, Path, StandardCopyOption}
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
      metadata.id == id.value && metadata.size.exists(size =>
        Files.isRegularFile(file) && Files.size(file) == size
      ) &&
      Instant.parse(metadata.createdAt).plus(config.retention.asJava).isAfter(Instant.now())
    }.getOrElse(false)

  def download(
      id: SvOnboardingSnapshotId,
      downloadFrom: SvOnboardingSnapshotId => Future[OnboardSvDownloadResponse],
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Path] =
    Future(blocking(completedDownload(id))).flatMap {
      case true => Future.successful(file)
      case false =>
        val metadata = DownloadMetadata(id.value, Instant.now().toString, None)
        Future(blocking(writeMetadata(metadata))).flatMap { _ =>
          downloadFrom(id)
            .recoverWith { case notFound @ HttpCommandException(_, StatusCodes.NotFound, _) =>
              Future.failed(SnapshotNotFound(notFound))
            }
            .flatMap { case OnboardSvDownloadResponse.OK(entity) =>
              entity.dataBytes
                .runWith(FileIO.toPath(file))
                .flatMap { result =>
                  Future(blocking {
                    if (entity.contentLengthOption.exists(_ != result.count))
                      throw new java.io.IOException("Incomplete onboarding snapshot download")
                    writeMetadata(metadata.copy(size = Some(result.count)))
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

  def delete(): Unit =
    File(directory).delete(swallowIOExceptions = true).discard
}

object SvOnboardingSnapshotDownload {

  private final case class DownloadMetadata(id: String, createdAt: String, size: Option[Long])
  private implicit val encoder: Encoder[DownloadMetadata] = deriveEncoder
  private implicit val decoder: Decoder[DownloadMetadata] = deriveDecoder

  final case class SnapshotNotFound(cause: HttpCommandException)
      extends RetryProvider.QuietNonRetryableException(cause.getMessage)

  def apply(
      config: SvOnboardingSnapshotsConfig,
      name: String,
  ): SvOnboardingSnapshotDownload = {
    new SvOnboardingSnapshotDownload(
      (File(config.directory) / "downloads" / name).createDirectories().path,
      config,
    )
  }
}

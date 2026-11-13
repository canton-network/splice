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
import org.apache.pekko.http.scaladsl.model.{ContentRange, ResponseEntity, StatusCodes}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{FileIO, Sink}
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.retry.{ErrorKind, ExceptionRetryPolicy}
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.environment.{RetryFor, RetryProvider}
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvStreamClient.{
  OnboardSvDownloadResponse
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingSnapshotsConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.{
  SvOnboardingSnapshotId,
  SvOnboardingSnapshotSha256,
}

import java.nio.file.StandardOpenOption.{APPEND, CREATE, TRUNCATE_EXISTING, WRITE}
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

  private def readMetadata(id: SvOnboardingSnapshotId): Option[DownloadMetadata] =
    Try {
      val metadata =
        decode[DownloadMetadata](Files.readString(metadataFile)).fold(throw _, identity)
      Option.when(
        metadata.id == id.value &&
          metadata.sha256Base64.forall(stored =>
            SvOnboardingSnapshotSha256.canonicalBase64(stored).contains(stored)
          ) &&
          Instant.parse(metadata.createdAt).plus(config.retention.asJava).isAfter(Instant.now())
      )(metadata)
    }.toOption.flatten

  def download(
      id: SvOnboardingSnapshotId,
      downloadFrom: (SvOnboardingSnapshotId, Long) => Future[OnboardSvDownloadResponse],
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Path] =
    Future(blocking {
      val previous = readMetadata(id)
      if (
        previous.exists(metadata =>
          metadata.size.exists(size => Files.isRegularFile(file) && Files.size(file) == size) &&
            metadata.sha256Base64.contains(fileSha256Base64())
        )
      ) None
      else
        previous.filter(metadata =>
          metadata.size.isEmpty && metadata.sha256Base64.isDefined && Files.isRegularFile(file)
        ) match {
          case Some(metadata) => Some(metadata -> Files.size(file))
          case None =>
            Files.createDirectories(directory)
            Files.deleteIfExists(file).discard
            val metadata = DownloadMetadata(id.value, Instant.now().toString, None, None, None)
            writeMetadata(metadata)
            Some(metadata -> 0L)
        }
    }).flatMap {
      case None => Future.successful(file)
      case Some((previous, offset)) =>
        downloadFrom(id, offset)
          .recoverWith {
            case error @ HttpCommandException(_, StatusCodes.NotFound | StatusCodes.Gone, _) =>
              Future.failed(PrepareAgain(error))
          }
          .flatMap(receive(id, previous, offset, _))
          .map(_ => file)
    }

  private def receive(
      id: SvOnboardingSnapshotId,
      previous: DownloadMetadata,
      offset: Long,
      response: OnboardSvDownloadResponse,
  )(implicit ec: ExecutionContext, mat: Materializer): Future[Unit] =
    response match {
      case OnboardSvDownloadResponse.Accepted(pending) =>
        Future.failed(
          Status.UNAVAILABLE
            .withDescription(
              s"Onboarding snapshot $id is ${pending.status}, queued at ${pending.queuedAt}" +
                pending.startedAt.fold("")(startedAt => s", started at $startedAt")
            )
            .asRuntimeException()
        )
      case OnboardSvDownloadResponse.OK(entity, reprDigest) =>
        withSha256(id, entity, reprDigest) { sha256Base64 =>
          val metadata =
            previous.copy(
              sha256Base64 = Some(sha256Base64),
              length = entity.contentLengthOption,
              size = None,
            )
          writeAndVerify(entity, metadata, append = false)
        }
      case OnboardSvDownloadResponse.PartialContent(entity, contentRange, reprDigest) =>
        withSha256(id, entity, reprDigest) { sha256Base64 =>
          contentRange match {
            case Some(ContentRange.Default(first, _, length))
                if first == offset && previous.sha256Base64.contains(sha256Base64) &&
                  previous.length.forall(expected => length.contains(expected)) =>
              writeAndVerify(entity, previous.copy(length = length), append = true)
            case _ =>
              cancel(entity)
              restart(
                s"partial response with Content-Range ${contentRange.fold("missing")(_.value)} and digest $sha256Base64 does not continue the download at offset $offset"
              )
          }
        }
      case OnboardSvDownloadResponse.RangeNotSatisfiable =>
        if (previous.sha256Base64.isDefined && previous.length.contains(offset)) verify(previous)
        else restart(s"range from offset $offset is not satisfiable")
    }

  private def withSha256(
      id: SvOnboardingSnapshotId,
      entity: ResponseEntity,
      reprDigest: Option[String],
  )(
      next: String => Future[Unit]
  )(implicit mat: Materializer): Future[Unit] =
    reprDigest.flatMap(SvOnboardingSnapshotSha256.base64FromReprDigest) match {
      case Some(sha256Base64) => next(sha256Base64)
      case None =>
        cancel(entity)
        val reason =
          if (reprDigest.isEmpty) "is missing the required Repr-Digest header"
          else "has an invalid SHA-256 Repr-Digest header"
        Future.failed(
          new RetryProvider.QuietNonRetryableException(
            s"Sponsor response for onboarding snapshot $id $reason. Check the sponsor or proxy configuration."
          )
        )
    }

  private def cancel(entity: ResponseEntity)(implicit mat: Materializer): Unit =
    entity.dataBytes.runWith(Sink.cancelled).discard

  private def writeAndVerify(entity: ResponseEntity, metadata: DownloadMetadata, append: Boolean)(
      implicit
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Unit] =
    Future(blocking(writeMetadata(metadata)))
      .recoverWith { case NonFatal(e) =>
        cancel(entity)
        Future.failed(e)
      }
      .flatMap(_ => write(entity, append))
      .flatMap(_ => verify(metadata))

  private def write(entity: ResponseEntity, append: Boolean)(implicit
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Unit] =
    entity.dataBytes
      .runWith(
        FileIO.toPath(
          file,
          if (append) Set(WRITE, APPEND, CREATE) else Set(WRITE, TRUNCATE_EXISTING, CREATE),
        )
      )
      .map(_ => ())
      .recoverWith { case NonFatal(e) =>
        Future.failed(
          Status.UNAVAILABLE
            .withDescription(s"Download of onboarding snapshot was interrupted: $e")
            .withCause(e)
            .asRuntimeException()
        )
      }

  private def verify(metadata: DownloadMetadata)(implicit ec: ExecutionContext): Future[Unit] =
    Future {
      blocking {
        val size = Files.size(file)
        metadata.length.filter(_ != size).foreach { expected =>
          throw Status.UNAVAILABLE
            .withDescription(s"Downloaded $size bytes of onboarding snapshot, expected $expected")
            .asRuntimeException()
        }
        val actual = fileSha256Base64()
        if (!metadata.sha256Base64.contains(actual)) {
          Files.delete(file)
          throw Status.UNAVAILABLE
            .withDescription(
              s"SHA-256 $actual of onboarding snapshot does not match Repr-Digest ${metadata.sha256Base64
                  .getOrElse("<missing>")}"
            )
            .asRuntimeException()
        }
        writeMetadata(metadata.copy(size = Some(size)))
      }
    }

  private def fileSha256Base64(): String =
    SvOnboardingSnapshotSha256.base64OfFile(file)

  private def restart(reason: String)(implicit ec: ExecutionContext): Future[Unit] =
    Future(blocking(Files.deleteIfExists(file).discard)).flatMap(_ =>
      Future.failed(
        Status.UNAVAILABLE
          .withDescription(s"Restarting the download of the onboarding snapshot: $reason")
          .asRuntimeException()
      )
    )

  def downloadWithRetries(
      id: SvOnboardingSnapshotId,
      downloadFrom: (SvOnboardingSnapshotId, Long) => Future[OnboardSvDownloadResponse],
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
            .withCause(PrepareAgain(cause))
            .asRuntimeException()
        )
      }

  def delete(): Unit =
    File(directory).delete(swallowIOExceptions = true).discard
}

object SvOnboardingSnapshotDownload {

  private final case class DownloadMetadata(
      id: String,
      createdAt: String,
      sha256Base64: Option[String],
      length: Option[Long],
      size: Option[Long],
  )
  private implicit val encoder: Encoder[DownloadMetadata] = deriveEncoder
  private implicit val decoder: Decoder[DownloadMetadata] = deriveDecoder

  final case class PrepareAgain(cause: HttpCommandException)
      extends RetryProvider.QuietNonRetryableException(cause.getMessage)

  final case class RetryOnPrepareAgain(operationName: String) extends ExceptionRetryPolicy {
    override def determineExceptionErrorKind(exception: Throwable, logger: TracedLogger)(implicit
        tc: TraceContext
    ): ErrorKind = exception.getCause match {
      case _: PrepareAgain =>
        logger.info(
          s"The operation $operationName needs a new onboarding snapshot: ${exception.getMessage}"
        )
        ErrorKind.TransientErrorKind()
      case _ =>
        logThrowable(exception, logger)
        ErrorKind.FatalErrorKind
    }
  }

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

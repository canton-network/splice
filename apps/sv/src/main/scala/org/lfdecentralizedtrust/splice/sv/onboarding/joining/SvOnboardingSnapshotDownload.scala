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
      val storedSha256Base64 = metadata.state match {
        case DownloadState.Started => None
        case DownloadState.Receiving(sha256Base64, _) => Some(sha256Base64)
        case DownloadState.Complete(sha256Base64, _) => Some(sha256Base64)
      }
      Option.when(
        metadata.id == id.value &&
          storedSha256Base64.forall(stored =>
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
      readMetadata(id) match {
        case Some(DownloadMetadata(_, _, DownloadState.Complete(sha256Base64, size)))
            if Files.isRegularFile(file) && Files.size(file) == size &&
              fileSha256Base64() == sha256Base64 =>
          None
        case Some(metadata @ DownloadMetadata(_, _, _: DownloadState.Receiving))
            if Files.isRegularFile(file) =>
          Some(metadata -> Files.size(file))
        case _ =>
          Files.createDirectories(directory)
          Files.deleteIfExists(file).discard
          val metadata = DownloadMetadata(id.value, Instant.now().toString, DownloadState.Started)
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
          val receiving = DownloadState.Receiving(sha256Base64, entity.contentLengthOption)
          writeAndVerify(id, entity, previous, receiving, resumed = false)
        }
      case OnboardSvDownloadResponse.PartialContent(entity, contentRange, reprDigest) =>
        withSha256(id, entity, reprDigest) { sha256Base64 =>
          (previous.state, contentRange) match {
            case (
                  DownloadState.Receiving(expectedSha256Base64, expectedLength),
                  Some(ContentRange.Default(first, _, length)),
                )
                if first == offset && expectedSha256Base64 == sha256Base64 &&
                  expectedLength.forall(expected => length.contains(expected)) =>
              val receiving = DownloadState.Receiving(sha256Base64, length)
              writeAndVerify(id, entity, previous, receiving, resumed = true)
            case _ =>
              cancel(entity)
              restart(
                s"partial response with Content-Range ${contentRange.fold("missing")(_.value)} and digest $sha256Base64 does not continue the download at offset $offset"
              )
          }
        }
      case OnboardSvDownloadResponse.RangeNotSatisfiable =>
        previous.state match {
          case receiving @ DownloadState.Receiving(_, Some(length)) if length == offset =>
            verify(id, previous, receiving, resumed = true)
          case _ => restart(s"range from offset $offset is not satisfiable")
        }
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

  private def writeAndVerify(
      id: SvOnboardingSnapshotId,
      entity: ResponseEntity,
      previous: DownloadMetadata,
      receiving: DownloadState.Receiving,
      resumed: Boolean,
  )(implicit
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Unit] =
    Future(blocking(writeMetadata(previous.copy(state = receiving))))
      .recoverWith { case NonFatal(e) =>
        cancel(entity)
        Future.failed(e)
      }
      .flatMap(_ => write(entity, append = resumed))
      .flatMap(_ => verify(id, previous, receiving, resumed))

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

  private def verify(
      id: SvOnboardingSnapshotId,
      previous: DownloadMetadata,
      receiving: DownloadState.Receiving,
      resumed: Boolean,
  )(implicit ec: ExecutionContext): Future[Unit] =
    Future {
      blocking {
        val size = Files.size(file)
        receiving.length.filter(_ != size).foreach { expected =>
          throw Status.UNAVAILABLE
            .withDescription(s"Downloaded $size bytes of onboarding snapshot, expected $expected")
            .asRuntimeException()
        }
        val actual = fileSha256Base64()
        if (actual != receiving.sha256Base64) {
          Files.delete(file)
          val mismatch =
            s"SHA-256 $actual of onboarding snapshot $id does not match Repr-Digest ${receiving.sha256Base64}"
          if (resumed)
            throw Status.UNAVAILABLE
              .withDescription(s"$mismatch after resuming; downloading it again from the start")
              .asRuntimeException()
          else
            throw new RetryProvider.QuietNonRetryableException(
              s"$mismatch after a full download. Check the sponsor's snapshot and any proxy between the SVs."
            )
        }
        writeMetadata(previous.copy(state = DownloadState.Complete(actual, size)))
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

  private sealed trait DownloadState
  private object DownloadState {
    case object Started extends DownloadState
    final case class Receiving(sha256Base64: String, length: Option[Long]) extends DownloadState
    final case class Complete(sha256Base64: String, size: Long) extends DownloadState
  }
  private implicit val startedEncoder: Encoder[DownloadState.Started.type] = deriveEncoder
  private implicit val startedDecoder: Decoder[DownloadState.Started.type] = deriveDecoder
  private implicit val receivingEncoder: Encoder[DownloadState.Receiving] = deriveEncoder
  private implicit val receivingDecoder: Decoder[DownloadState.Receiving] = deriveDecoder
  private implicit val completeEncoder: Encoder[DownloadState.Complete] = deriveEncoder
  private implicit val completeDecoder: Decoder[DownloadState.Complete] = deriveDecoder
  private implicit val stateEncoder: Encoder[DownloadState] = deriveEncoder
  private implicit val stateDecoder: Decoder[DownloadState] = deriveDecoder

  private final case class DownloadMetadata(id: String, createdAt: String, state: DownloadState)
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

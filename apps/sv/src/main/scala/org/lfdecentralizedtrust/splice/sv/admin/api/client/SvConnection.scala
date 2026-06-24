// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.api.client

import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import org.apache.pekko.http.scaladsl.model.headers.{
  ByteRange,
  Range as RangeHeader,
  `Content-Range`,
}
import org.apache.pekko.http.scaladsl.model.{ContentRange, HttpMethods, HttpRequest, StatusCodes}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.FileIO
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.{HttpAppConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.sv.admin.api.client.commands.HttpSvPublicAppClient
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import java.nio.file.{Files, OpenOption, Path, StandardOpenOption}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

final class SvConnection private (
    config: NetworkAppClientConfig,
    upgradesConfig: UpgradesConfig,
    retryProvider: RetryProvider,
    loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
) extends HttpAppConnection(config, upgradesConfig, "sv", retryProvider, loggerFactory) {

  /** Ask the SV to start the onboarding of a new SV with an encoded (and signed) onboarding token.
    */
  def startSvOnboarding(token: String)(implicit
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Unit] =
    runHttpCmd(config.url, HttpSvPublicAppClient.StartSvOnboarding(token))

  /** Step 1 of the resumable party migration: ask the sponsoring SV to authorize hosting the DSO
    * party at the candidate participant and to start exporting the ACS snapshot to a file. The
    * snapshot is fetched separately via [[downloadDsoPartyAcsSnapshot]].
    */
  def initiateDsoPartyMigration(
      candidateParticipantId: ParticipantId,
      candidateParty: PartyId,
  )(implicit
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Either[
    HttpSvPublicAppClient.OnboardSvPartyMigrationProposalNotFound,
    Unit,
  ]] =
    runHttpCmd(
      config.url,
      HttpSvPublicAppClient.OnboardSvPartyMigrationInitiate(
        candidateParticipantId,
        candidateParty,
      ),
    )

  /** Step 2 of the resumable party migration: perform a single, range-resumable download attempt
    * of the DSO party ACS snapshot prepared by [[initiateDsoPartyMigration]], streaming the bytes
    * straight into `outputFile` so the snapshot is never held in memory. If `outputFile` already
    * holds a partial download, the request resumes from that byte offset. The caller is expected
    * to retry until [[SvConnection.SnapshotDownloadAttempt.Complete]] is returned.
    */
  def downloadDsoPartyAcsSnapshot(
      candidateParty: PartyId,
      outputFile: Path,
  )(implicit
      httpClient: HttpClient,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[SvConnection.SnapshotDownloadAttempt] = {
    import SvConnection.SnapshotDownloadAttempt
    val offset = if (Files.exists(outputFile)) Files.size(outputFile) else 0L
    if (offset > 0)
      logger.info(
        s"Resuming download of DSO party ACS snapshot for $candidateParty into $outputFile from offset $offset"
      )
    else
      logger.info(
        s"Downloading fresh DSO party ACS snapshot for $candidateParty into $outputFile"
      )
    val uri = config.url.withPath(
      config.url.path / "api" / "sv" / "v0" / "onboard" / "sv" / "party-migration" / "snapshot" / candidateParty.toProtoPrimitive
    )
    val rangeHeaders =
      if (offset > 0) List(RangeHeader(ByteRange.fromOffset(offset))) else Nil
    val request = HttpRequest(method = HttpMethods.GET, uri = uri, headers = rangeHeaders)
    httpClient
      .executeRequest("sv", "onboardSvPartyMigrationSnapshotDownload")(request)
      .flatMap { response =>
        response.status match {
          case StatusCodes.OK | StatusCodes.PartialContent =>
            val isPartial = response.status == StatusCodes.PartialContent
            val total =
              if (isPartial)
                response
                  .header[`Content-Range`]
                  .flatMap(_.contentRange match {
                    case ContentRange.Default(_, _, instanceLength) => instanceLength
                    case _ => None
                  })
              else response.entity.contentLengthOption
            // Append when resuming a partial download, otherwise (re)write from the start.
            val openOptions: Set[OpenOption] =
              if (isPartial && offset > 0)
                Set(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
              else
                Set(
                  StandardOpenOption.WRITE,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.TRUNCATE_EXISTING,
                )
            response.entity.dataBytes
              .runWith(FileIO.toPath(outputFile, openOptions))
              .map { _ =>
                val written = Files.size(outputFile)
                if (total.contains(written)) SnapshotDownloadAttempt.Complete
                else SnapshotDownloadAttempt.Incomplete(written, total)
              }
          case StatusCodes.RangeNotSatisfiable =>
            // We have already downloaded the whole snapshot.
            response.discardEntityBytes().discard
            Future.successful(SnapshotDownloadAttempt.Complete)
          case StatusCodes.Conflict =>
            response.discardEntityBytes().discard
            Future.successful(SnapshotDownloadAttempt.NotReady)
          case StatusCodes.NotFound =>
            response.discardEntityBytes().discard
            Future.successful(SnapshotDownloadAttempt.Absent)
          case other =>
            Unmarshal(response.entity)
              .to[String]
              .map(body => SnapshotDownloadAttempt.Failed(s"Unexpected status $other: $body"))
        }
      }
  }

  def onboardSvSequencer(
      sequencerId: SequencerId
  )(implicit
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[ByteString] =
    runHttpCmd(
      config.url,
      HttpSvPublicAppClient.OnboardSvSequencer(
        sequencerId
      ),
    )

  def getDsoInfo()(implicit
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[HttpSvPublicAppClient.DsoInfo] =
    runHttpCmd(
      config.url,
      HttpSvPublicAppClient.GetDsoInfo,
    )

  def getMigrationId()(implicit
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Long] =
    runHttpCmd(
      config.url,
      HttpSvPublicAppClient.GetMigrationId,
    )

}

object SvConnection {

  /** Outcome of a single [[SvConnection.downloadDsoPartyAcsSnapshot]] attempt. */
  sealed trait SnapshotDownloadAttempt
  object SnapshotDownloadAttempt {

    /** The snapshot file has been fully downloaded. */
    case object Complete extends SnapshotDownloadAttempt

    /** Some bytes were downloaded but the snapshot is not complete yet; retry to resume. */
    final case class Incomplete(written: Long, total: Option[Long]) extends SnapshotDownloadAttempt

    /** The sponsor is still generating the snapshot; retry later. */
    case object NotReady extends SnapshotDownloadAttempt

    /** The sponsor has no snapshot for this candidate (e.g. it was lost on a restart); the
      * candidate needs to re-initiate.
      */
    case object Absent extends SnapshotDownloadAttempt

    /** The sponsor reported a failure preparing the snapshot. */
    final case class Failed(message: String) extends SnapshotDownloadAttempt
  }

  def apply(
      config: NetworkAppClientConfig,
      upgradesConfig: UpgradesConfig,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
      retryConnectionOnInitialFailure: Boolean = true,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
  ): Future[SvConnection] =
    HttpAppConnection.checkVersionOrClose(
      new SvConnection(config, upgradesConfig, retryProvider, loggerFactory),
      retryConnectionOnInitialFailure,
    )
}

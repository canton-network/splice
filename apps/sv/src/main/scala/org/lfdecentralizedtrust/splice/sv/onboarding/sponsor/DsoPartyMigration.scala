// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.sponsor

import cats.data.EitherT
import com.digitalasset.base.error.utils.ErrorDetails
import com.digitalasset.canton.discard.Implicits.DiscardOps
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.ExternalPartyAmuletRules
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
  SpliceLedgerClient,
}
import com.digitalasset.canton.participant.admin.party.PartyManagementServiceError
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting.DsoPartyMigrationFailure
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import io.grpc.StatusRuntimeException
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant
import scala.annotation.unused
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

class DsoPartyMigration(
    svStoreWithIngestion: AppStoreWithIngestion[SvSvStore],
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    participantAdminConnection: ParticipantAdminConnection,
    @unused ledgerClient: SpliceLedgerClient,
    retryProvider: RetryProvider,
    dsoPartyHosting: DsoPartyHosting,
    acsSnapshotDir: Path,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends NamedLogging {

  import DsoPartyMigration.*

  private val dsoStore = dsoStoreWithIngestion.store
  private val dsoParty = dsoStore.key.dsoParty
  private val svParty = dsoStore.key.svParty
  private val partyHosting = new SponsorDsoPartyHosting(
    participantAdminConnection,
    dsoParty,
    dsoPartyHosting,
    loggerFactory,
  )

  Files.createDirectories(acsSnapshotDir).discard

  /** Tracks in-flight and failed exports keyed by the candidate party. A completed export is not
    * tracked here; its presence is signalled by the existence of the final snapshot file on disk.
    */
  private val exports = new TrieMap[PartyId, ExportProgress]()

  /** The file the snapshot for `candidate` is (or will be) written to. Party ids cannot contain a
    * path separator, so they are safe to use directly in the file name. The sponsor party
    * (`svParty`) is included so that several SVs sharing an `acsSnapshotDir` (e.g. local
    * multi-SV test runs) don't collide on the same file.
    */
  def snapshotFilePath(candidate: PartyId): Path =
    acsSnapshotDir.resolve(
      s"dso-party-acs-${svParty.toProtoPrimitive}-${candidate.toProtoPrimitive}.acs"
    )

  private def tmpFilePath(finalFile: Path): Path =
    finalFile.resolveSibling(finalFile.getFileName.toString + ".tmp")

  /** State of the snapshot for `candidate`, used by the download endpoint to decide what to
    * respond.
    */
  def snapshotState(candidate: PartyId): SnapshotState = {
    val file = snapshotFilePath(candidate)
    if (Files.exists(file)) SnapshotState.Ready(file)
    else
      exports.get(candidate) match {
        case Some(ExportProgress.Running) => SnapshotState.InProgress
        case Some(ExportProgress.Failed(message)) => SnapshotState.Failed(message)
        case None => SnapshotState.NotFound
      }
  }

  /** Step 1 of the resumable party migration: synchronously authorize hosting the DSO party on
    * the candidate participant (this is where proposal-not-found surfaces, preserving the
    * existing serial-based retry behaviour) and then kick off the ACS snapshot export to a file in
    * the background. Returns as soon as the export has been started (or is already running /
    * complete). The candidate downloads the resulting file via the resumable download endpoint.
    */
  def initiateSnapshot(
      candidate: PartyId,
      participantId: ParticipantId,
  )(implicit tc: TraceContext): EitherT[Future, DsoPartyMigrationFailure, Unit] = {
    logger.info(s"Sponsor SV initiating DSO party snapshot for $candidate on $participantId")
    val file = snapshotFilePath(candidate)
    if (Files.exists(file)) {
      logger.info(s"ACS snapshot for $candidate already available at $file, nothing to do")
      EitherT.rightT[Future, DsoPartyMigrationFailure](())
    } else if (exports.get(candidate).contains(ExportProgress.Running)) {
      logger.info(s"ACS snapshot export for $candidate already in progress, nothing to do")
      EitherT.rightT[Future, DsoPartyMigrationFailure](())
    } else {
      for {
        dsoRules <- EitherT.liftF(dsoStore.getDsoRules())
        // this will wait until the PartyToParticipant state change completed
        _ <- partyHosting
          .authorizeDsoPartyToParticipant(
            dsoRules.domain,
            participantId,
          )
        activationTx <- EitherT.liftF(
          participantAdminConnection
            .getDsoPartyToParticipantTransaction(
              dsoRules.domain,
              participantId,
              dsoParty,
            )
            .getOrElseF(
              Future.failed(
                Status.NOT_FOUND
                  .withDescription(
                    s"Transaction where the participant $participantId was activated not found."
                  )
                  .asRuntimeException()
              )
            )
        )
        activationTime = activationTx.base.validFrom
      } yield {
        logger.info(
          s"DSO party was authorized on $participantId, starting background ACS snapshot export at time $activationTime."
        )
        startBackgroundExportIfNeeded(candidate, participantId, activationTime, dsoRules.domain)
      }
    }
  }

  private def startBackgroundExportIfNeeded(
      candidate: PartyId,
      participantId: ParticipantId,
      activationTime: Instant,
      decentralizedSynchronizer: SynchronizerId,
  )(implicit tc: TraceContext): Unit = {
    val file = snapshotFilePath(candidate)
    // Atomically claim the export so that two concurrent initiate calls don't both spawn one. A
    // previously failed entry is replaced (via CAS) here so that a retry re-runs the export.
    val shouldStart =
      if (Files.exists(file)) false
      else
        exports.get(candidate) match {
          case Some(ExportProgress.Running) => false
          case Some(failed: ExportProgress.Failed) =>
            exports.replace(candidate, failed, ExportProgress.Running)
          case None =>
            exports.putIfAbsent(candidate, ExportProgress.Running).isEmpty
        }
    if (shouldStart)
      runBackgroundExport(candidate, participantId, activationTime, decentralizedSynchronizer)
    else
      logger.info(
        s"ACS snapshot export for $candidate already in progress or complete, not starting another"
      )
  }

  private def runBackgroundExport(
      candidate: PartyId,
      participantId: ParticipantId,
      activationTime: Instant,
      decentralizedSynchronizer: SynchronizerId,
  )(implicit tc: TraceContext): Unit = {
    val finalFile = snapshotFilePath(candidate)
    val tmpFile = tmpFilePath(finalFile)
    val exportF = downloadSnapshotToFile(
      participantId,
      activationTime,
      decentralizedSynchronizer,
      tmpFile,
    ).map { _ =>
      // Atomically publish the completed snapshot so the download endpoint never serves a
      // partially-written file.
      Files.move(tmpFile, finalFile, StandardCopyOption.ATOMIC_MOVE).discard
    }
    exportF.onComplete {
      case Success(_) =>
        exports.remove(candidate).discard
        logger.info(s"Completed ACS snapshot export for $candidate to $finalFile")
      case Failure(e) =>
        Try(Files.deleteIfExists(tmpFile)).discard
        exports.update(candidate, ExportProgress.Failed(Option(e.getMessage).getOrElse(e.toString)))
        logger.warn(s"Failed to export ACS snapshot for $candidate", e)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def downloadSnapshotToFile(
      targetParticipantId: ParticipantId,
      activationTime: Instant,
      decentralizedSynchronizer: SynchronizerId,
      outputFile: Path,
  )(implicit tc: TraceContext): Future[Unit] = {

    def submitDummyTransaction(): Future[Unit] =
      svStoreWithIngestion
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(
          Seq(svParty),
          Seq.empty,
          // The transaction here is arbitrary.
          // ExternalPartyAmuletRules just happens to be one of the simplest templates we have.
          new ExternalPartyAmuletRules(svParty.toProtoPrimitive).createAnd
            .exerciseArchive(),
        )
        .withSynchronizerId(decentralizedSynchronizer)
        .noDedup
        .yieldUnit()

    retryProvider.retry(
      RetryFor.ClientCalls,
      "download_acs_snapshot",
      show"Download ACS snapshot for DSO at $activationTime",
      participantAdminConnection
        .exportPartyAcsToFile(
          dsoParty,
          synchronizerId = decentralizedSynchronizer,
          targetParticipantId = targetParticipantId,
          activationTime = activationTime,
          outputFile = outputFile,
        )
        .recoverWith { case ex: StatusRuntimeException =>
          val errorDetails = ErrorDetails.from(ex: StatusRuntimeException)
          for {
            _ <- MonadUtil.sequentialTraverse_(errorDetails) {
              case ErrorDetails.ErrorInfoDetail(
                    PartyManagementServiceError.UnprocessedRequestedTimestamp.id,
                    metadata,
                  ) =>
                logger.info(
                  s"Requested record time $activationTime is not yet clean: $metadata, submitting dummy transaction"
                )
                submitDummyTransaction()
              case _ => Future.unit
            }
          } yield {
            // rethrow to trigger the retry
            throw ex
          }
        },
      logger,
    )
  }

}

object DsoPartyMigration {

  private sealed trait ExportProgress
  private object ExportProgress {
    case object Running extends ExportProgress
    final case class Failed(message: String) extends ExportProgress
  }

  /** State of a candidate's ACS snapshot as observed by the sponsor. */
  sealed trait SnapshotState
  object SnapshotState {

    /** The snapshot has been fully written and is available at `file`. */
    final case class Ready(file: Path) extends SnapshotState

    /** The snapshot is currently being exported. */
    case object InProgress extends SnapshotState

    /** The last export attempt failed; the candidate should re-initiate. */
    final case class Failed(message: String) extends SnapshotState

    /** No snapshot is known for this candidate (e.g. initiate was never called, or it was lost on
      * a sponsor restart); the candidate should (re-)initiate.
      */
    case object NotFound extends SnapshotState
  }
}
